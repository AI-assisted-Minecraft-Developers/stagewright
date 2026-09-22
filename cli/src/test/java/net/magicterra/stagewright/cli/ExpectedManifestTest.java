package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code --expect}, read with the engine's rules so this CLI and the Gradle plugin agree on it. */
class ExpectedManifestTest {

    @Test
    void noExpectMeansNoReconciliation() {
        assertNull(Main.expected(Map.of()));
    }

    @Test
    void theManifestsNamesAreWhatTheRunIsReconciledAgainst(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("expected.txt");
        Files.writeString(file, "wd.a\nwd.b # trailing\n", StandardCharsets.UTF_8);
        assertEquals(List.of("wd.a", "wd.b"), Main.expected(Map.of("expect", file.toString())));
    }

    @Test
    void aManifestOfCommentsIsRefusedLikeThePluginRefusesIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("expected.txt");
        Files.writeString(file, "# wd.a\n# wd.b\n", StandardCharsets.UTF_8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Main.expected(Map.of("expect", file.toString())));
        assertTrue(e.getMessage().contains("names no scenes"), e.getMessage());
    }
}
