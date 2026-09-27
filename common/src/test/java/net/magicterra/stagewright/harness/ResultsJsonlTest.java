package net.magicterra.stagewright.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void theHeaderNamesTheBuildTheLauncherPassed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("results.jsonl");
        String before = System.getProperty("stagewright.build");
        System.setProperty("stagewright.build", "git:0123456789ab");
        try {
            new ResultsJsonl(file).writeSuiteHeader("fabric", List.of());
        } finally {
            if (before == null) System.clearProperty("stagewright.build");
            else System.setProperty("stagewright.build", before);
        }
        String header = Files.readString(file);
        assertTrue(header.contains("\"build\":\"git:0123456789ab\""), header);
        assertTrue(header.contains("\"startedAt\":"), header);
    }

    @Test
    void aHeaderWrittenAfterTheRunStillSaysWhenItStarted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("results.jsonl");
        new ResultsJsonl(file).writeSuiteHeader("fabric", List.of(), 1_700_000_000_000L);
        String header = Files.readString(file);
        assertTrue(header.contains("\"startedAt\":1700000000000"), header);
    }

    @Test
    void aRecordWhoseBodyRanOmitsTheField(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("results.jsonl");
        new ResultsJsonl(file).writeScene("a", SceneOutcome.PASS, 5, 10, "", Map.of(), false, true);
        assertFalse(Files.readString(file).contains("bodyRan"), Files.readString(file));
    }
}
