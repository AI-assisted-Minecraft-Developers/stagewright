package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.magicterra.stagewright.contract.DriverBinding;
import net.magicterra.stagewright.contract.SceneFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The attached half's scene files, loaded and run the way {@code --attached} runs them. */
class AttachedScenesTest {

    private static final DriverBinding NO_DRIVER = (method, params) -> {
        throw new SceneFailure("no driver in this test: " + method);
    };

    @Test
    void aSceneFileThatDoesNotParseIsRedNotEnv(@TempDir Path dir) throws IOException {
        // An authoring error, fully deterministic: exit 3 would read as "the host did not start".
        Path scenes = Files.createDirectories(dir.resolve("attached"));
        Files.writeString(scenes.resolve("broken.js"), "scene('pack.a', 20, function (s) {",
                StandardCharsets.UTF_8);
        List<String> log = new ArrayList<>();
        int code = Main.runAttachedScenes(scenes, NO_DRIVER, "fabric",
                dir.resolve("attached-results.jsonl"), log::add);
        assertEquals(1, code);
        assertTrue(log.stream().anyMatch(l -> l.contains("broken.js")), log.toString());
    }

    @Test
    void sceneFilesThatLoadAreRun(@TempDir Path dir) throws IOException {
        Path scenes = Files.createDirectories(dir.resolve("attached"));
        Files.writeString(scenes.resolve("ok.js"),
                "scene('pack.a', 20, function (s) { s.check(1).isEqualTo(1); });",
                StandardCharsets.UTF_8);
        Path results = dir.resolve("attached-results.jsonl");
        assertEquals(0, Main.runAttachedScenes(scenes, NO_DRIVER, "fabric", results, l -> { }));
        assertTrue(Files.readString(results).contains("\"name\":\"pack.a\""));
    }

    @Test
    void theAttachedHalfRecordsNoBuild(@TempDir Path dir) throws IOException {
        // A digest of mods/ alone misses the scene files and whatever is injected from elsewhere,
        // and a build that names part of the code lets coverage reconcile runs of different code.
        Path scenes = Files.createDirectories(dir.resolve("attached"));
        Files.writeString(scenes.resolve("ok.js"), "scene('pack.a', 20, function (s) { });",
                StandardCharsets.UTF_8);
        Path results = dir.resolve("attached-results.jsonl");
        Main.runAttachedScenes(scenes, NO_DRIVER, "fabric", results, l -> { });
        String header = Files.readAllLines(results).get(0);
        assertTrue(header.contains("\"startedAt\":") && !header.contains("\"build\""), header);
    }

    @Test
    void anInProcessSuiteThatCannotBeStartedIsReportedNotThrown() {
        // Thrown, it would reach main and exit 3 before either verdict line was printed.
        List<String> err = new ArrayList<>();
        assertFalse(Main.triggerInProcessSuite(NO_DRIVER, err::add));
        assertTrue(err.stream().anyMatch(l -> l.contains("did not go through")
                && l.contains("mc.test.run")), err.toString());
        assertTrue(Main.triggerInProcessSuite((method, params) -> null, err::add));
    }

    @Test
    void anInProcessSuiteThatWasNotStartedIsEnvWithoutBeingJudged(@TempDir Path dir) throws IOException {
        // Judged, a missing results file draws guesses about a jar that never loaded.
        Main.Judgement neverJudged = () -> {
            throw new AssertionError("judged a suite that never started");
        };
        Path log = dir.resolve("stagewright-run.log");
        List<String> out = new ArrayList<>();
        assertEquals(3, Main.inProcessCode(false, neverJudged, null, log, out::add));
        assertTrue(out.contains("[stagewright] log: " + log), out.toString());

        // A crash report is the game's own account, and what a reader should open first.
        Path crash = dir.resolve("crash-reports/crash-server.txt");
        out.clear();
        assertEquals(3, Main.inProcessCode(false, neverJudged, crash, log, out::add));
        assertTrue(out.stream().anyMatch(l -> l.endsWith(crash.toString())), out.toString());

        assertEquals(1, Main.inProcessCode(true, () -> 1, null, log, out::add));
    }
}
