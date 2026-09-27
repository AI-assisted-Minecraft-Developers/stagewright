package net.magicterra.stagewright.contract;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What {@link Scripts#load} accepts as a scene file. */
class ScriptsTest {

    @Test
    void aFileThatRegistersNoSceneStopsTheLoad(@TempDir Path dir) throws IOException {
        // Loaded and ignored, it is a file its author believes is running while nothing in it
        // does, and the suite stays green whatever the file says.
        Files.writeString(dir.resolve("a.js"), "scene('pack.a', 20, function (s) { });",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("forgot.js"), "function body(s) { s.check(1).isEqualTo(2); }",
                StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Scripts.load(dir, (cx, scope, file) -> { }, line -> { }));
        assertTrue(e.getMessage().contains("forgot.js"), e.getMessage());
    }
}
