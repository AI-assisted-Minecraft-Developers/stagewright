package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Telling this run's crash report from the four already in the folder. */
class CrashWatchTest {

    @Test
    void aDirectoryThatDoesNotExistYetIsNotACrash(@TempDir Path gameDir) {
        assertNull(CrashWatch.on(gameDir).fresh());
    }

    @Test
    void reportsFromPreviousRunsAreNotThisRunsCrash(@TempDir Path gameDir) throws IOException {
        report(gameDir, "crash-2026-07-03_16.28.01-client.txt");
        report(gameDir, "crash-2026-07-03_16.32.39-client.txt");

        CrashWatch watch = CrashWatch.on(gameDir);

        assertNull(watch.fresh());
        assertTrue(watch.describe().contains("no crash report"));
    }

    @Test
    void aReportWrittenAfterTheLaunchIsThisRunsCrash(@TempDir Path gameDir) throws IOException {
        report(gameDir, "crash-2026-07-03_16.28.01-client.txt");
        CrashWatch watch = CrashWatch.on(gameDir);

        report(gameDir, "crash-2026-09-11_01.21.52-client.txt");

        Path fresh = watch.fresh();
        assertNotNull(fresh);
        assertEquals("crash-2026-09-11_01.21.52-client.txt", fresh.getFileName().toString());
        assertTrue(watch.describe().contains("crash-2026-09-11_01.21.52-client.txt"));
    }

    private static void report(Path gameDir, String name) throws IOException {
        Path directory = gameDir.resolve("crash-reports");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), "---- Minecraft Crash Report ----\n");
    }
}
