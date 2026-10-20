package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The comparison between the two homes' results for the same scene file. */
class ParityTest {
    private static Map<String, Parity.Result> read(Path dir, String name, String... rows) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, List.of(rows), StandardCharsets.UTF_8);
        return Parity.read(file);
    }

    @Test
    void aSceneThatPassesInOneHomeAndFailsInTheOtherIsADifference(@TempDir Path dir) throws IOException {
        var inProcess = read(dir, "a.jsonl",
                "{\"type\":\"header\"}",
                "{\"type\":\"scene\",\"name\":\"pack.a\",\"outcome\":\"PASS\"}",
                "{\"type\":\"scene\",\"name\":\"pack.b\",\"outcome\":\"PASS\"}");
        var attached = read(dir, "b.jsonl",
                "{\"type\":\"scene\",\"name\":\"pack.a\",\"outcome\":\"PASS\"}",
                "{\"type\":\"scene\",\"name\":\"pack.b\",\"outcome\":\"FAIL\"}");

        Parity.Report report = Parity.compare(inProcess, attached);

        assertFalse(report.agrees());
        assertEquals(List.of("'pack.b': passed in process, did not pass attached"), report.differences());
        assertEquals(2, report.compared());
    }

    @Test
    void everyKindOfNotPassingIsOneKind(@TempDir Path dir) throws IOException {
        var inProcess = read(dir, "a.jsonl", "{\"type\":\"scene\",\"name\":\"pack.a\",\"outcome\":\"TIMEOUT\"}");
        var attached = read(dir, "b.jsonl", "{\"type\":\"scene\",\"name\":\"pack.a\",\"outcome\":\"FAIL\"}");

        assertTrue(Parity.compare(inProcess, attached).agrees(),
                "a tick budget has no wall-clock twin, so TIMEOUT and FAIL are the same answer: it did not pass");
    }

    @Test
    void aSkipIsNotAPass(@TempDir Path dir) throws IOException {
        var inProcess = read(dir, "a.jsonl",
                "{\"type\":\"scene\",\"name\":\"pack.a\",\"outcome\":\"PASS\",\"skipped\":true}");
        var attached = read(dir, "b.jsonl", "{\"type\":\"scene\",\"name\":\"pack.a\",\"outcome\":\"PASS\"}");

        assertEquals(List.of("'pack.a': skipped in process, passed attached"),
                Parity.compare(inProcess, attached).differences());
    }

    @Test
    void aSceneInOnlyOneHomeIsCountedNotJudged(@TempDir Path dir) throws IOException {
        var inProcess = read(dir, "a.jsonl",
                "{\"type\":\"scene\",\"name\":\"wd.other\",\"outcome\":\"FAIL\"}",
                "{\"type\":\"scene\",\"name\":\"pack.a\",\"outcome\":\"PASS\"}");
        var attached = read(dir, "b.jsonl",
                "{\"type\":\"scene\",\"name\":\"pack.a\",\"outcome\":\"PASS\"}",
                "{\"type\":\"scene\",\"name\":\"pack.attachedOnly\",\"outcome\":\"PASS\"}");

        Parity.Report report = Parity.compare(inProcess, attached);

        assertTrue(report.agrees());
        assertEquals(1, report.compared());
        assertEquals(1, report.onlyInProcess());
        assertEquals(1, report.onlyAttached());
        assertTrue(Parity.lines(report).get(0).contains("PARITY agrees: 1 scene(s) ran in both homes"));
    }

    @Test
    void aMissingFileHasNothingToCompare(@TempDir Path dir) {
        assertEquals(Map.of(), Parity.read(dir.resolve("none.jsonl")));
    }
}
