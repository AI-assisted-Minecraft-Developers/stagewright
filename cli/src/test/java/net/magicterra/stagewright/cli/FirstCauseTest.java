package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which line of a failed boot's debug.log is reported as where it started. */
class FirstCauseTest {

    private static Path debugLog(Path gameDir, String... lines) throws IOException {
        Path log = Files.createDirectories(gameDir.resolve("logs")).resolve("debug.log");
        return Files.writeString(log, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void theFailureItselfBeatsTheBrokenStateThatFollowsIt(@TempDir Path gameDir) throws IOException {
        debugLog(gameDir,
                "[28九月2026 05:09:06.100] [main/INFO] loading",
                "[28九月2026 05:09:06.900] [Render thread/WARN] Cowardly refusing to send event to a broken mod state",
                "[28九月2026 05:09:06.912] [Render thread/FATAL] [net.neoforged.fml.ModLoader/CORE]: Error during"
                        + " pre-loading phase: Mod File mods/emixx.jar needs language provider kotlinforforge:5.3",
                "[28九月2026 05:09:07.000] [Render thread/WARN] Cowardly refusing to send event to a broken mod state");

        List<String> found = FirstCause.find(gameDir, 0);

        assertTrue(found.get(1).contains("needs language provider kotlinforforge"), found.toString());
        assertTrue(found.get(1).contains("28九月2026"), "decoded as UTF-8: " + found.get(1));
    }

    @Test
    void theBrokenStateStandsInWhenNothingElseWasLogged(@TempDir Path gameDir) throws IOException {
        debugLog(gameDir, "ok", "Cowardly refusing to send event to a broken mod state", "after");

        assertTrue(FirstCause.find(gameDir, 0).get(1).contains("broken mod state"));
    }

    @Test
    void aLogFromBeforeTheRunSaysNothing(@TempDir Path gameDir) throws IOException {
        debugLog(gameDir, "Failed to create mod instance");

        assertEquals(List.of(), FirstCause.find(gameDir, System.currentTimeMillis() + 60_000));
    }
}
