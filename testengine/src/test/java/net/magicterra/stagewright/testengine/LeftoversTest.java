package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class LeftoversTest {

    /** Shorter than a run's, so a test with a process that stays does not wait out the real one. */
    private static final Duration SETTLE = Duration.ofSeconds(3);

    @Test
    void aDirectoryAndWhatIsBelowItAreTheScenes() {
        List<String> roots = List.of("/runs/smoke");
        assertTrue(Leftovers.under("/runs/smoke", roots));
        assertTrue(Leftovers.under("/runs/smoke/game", roots));
        assertFalse(Leftovers.under("/runs/smoke-two", roots), "another scene's directory");
        assertFalse(Leftovers.under("/runs", roots));
    }

    @Test
    void aRemovedDirectoryStillCounts() {
        // What /proc says of the cwd of a process whose run directory was cleaned up under it.
        assertTrue(Leftovers.under("/runs/smoke (deleted)", List.of("/runs/smoke")));
    }

    @Test
    void aCommandLineNamesTheDirectoryOnlyAsAWholePath() {
        assertTrue(Leftovers.names("java --gameDir /runs/smoke", "/runs/smoke"));
        assertTrue(Leftovers.names("java -cp /runs/smoke/app.jar Main", "/runs/smoke"));
        assertTrue(Leftovers.names("java -Ddir=/runs/smoke -jar x", "/runs/smoke"));
        assertFalse(Leftovers.names("java --gameDir /runs/smokeFast", "/runs/smoke"), "another scene's directory");
        assertFalse(Leftovers.names("java --gameDir /runs/smoke-peer-b", "/runs/smoke"), "its peer's directory");
        assertTrue(Leftovers.names("a /runs/smokeFast b /runs/smoke", "/runs/smoke"), "found past a longer name");
        assertTrue(Leftovers.names("rsync \"/runs/smoke\" x", "/runs/smoke"), "quoted");
        assertFalse(Leftovers.names("rsync /backup/runs/smoke x", "/runs/smoke"), "the tail of another path");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aProcessThatIsStillExitingIsNotReported(@TempDir Path dir) throws Exception {
        Process ending =
                new ProcessBuilder("sleep", "1").directory(dir.toFile()).start();
        assertTrue(Leftovers.in(List.of(dir), Set.of(), Instant.MIN, SETTLE).isEmpty());
        assertTrue(ending.waitFor(5, TimeUnit.SECONDS), "it had exited when the scan gave up on it");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aProcessThatWasThereBeforeTheGameStartedIsNotItsLeftover(@TempDir Path dir) throws Exception {
        // As one an earlier run left in the scene's directory, which this run made again.
        Process earlier =
                new ProcessBuilder("sleep", "60").directory(dir.toFile()).start();
        try {
            Instant gameStarted =
                    earlier.toHandle().info().startInstant().orElseThrow().plusSeconds(1);
            assertTrue(Leftovers.in(List.of(dir), Set.of(), gameStarted, SETTLE).isEmpty());
        } finally {
            earlier.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aProcessRunningInTheDirectoryIsFoundAndTheGamesAreNot(@TempDir Path dir) throws Exception {
        Path run = Files.createDirectories(dir.resolve("runs").resolve("smoke"));
        Process left = new ProcessBuilder("sleep", "60").directory(run.toFile()).start();
        Process game = new ProcessBuilder("sleep", "60").directory(run.toFile()).start();
        try {
            List<String> found = Leftovers.in(List.of(run), Set.of(game.pid()), Instant.MIN, SETTLE);
            assertEquals(1, found.size(), found.toString());
            assertTrue(found.get(0).startsWith("pid " + left.pid() + ": "), found.toString());
            assertTrue(Leftovers.in(List.of(dir.resolve("elsewhere")), Set.of(), Instant.MIN, SETTLE)
                    .isEmpty());
        } finally {
            left.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            game.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }
}
