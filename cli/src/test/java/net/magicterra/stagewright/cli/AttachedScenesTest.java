package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
