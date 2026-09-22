package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the installer may and may not do to a {@code mods/} folder it does not own.
 *
 * <p>The CLI points this at a modpack's real server directory, so every jar already in there is the
 * pack's until the ledger says otherwise. Overwriting one by name is not an install, it is the first
 * half of deleting it: the name goes into the ledger, and the next run's sweep takes it out.
 */
class ModInstallTest {

    private static Path jar(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void aJarThePackShipsIsNeitherOverwrittenNorLedgered(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = gameDir.resolve("mods");
        jar(mods, "worlddriver-0.1.0.jar", "the pack's own copy");
        Path mine = jar(tmp.resolve("build"), "worlddriver-0.1.0.jar", "the author's build");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ModInstall.install(gameDir, List.of(mine), line -> {}));

        assertTrue(refused.getMessage().contains("worlddriver-0.1.0.jar"), refused.getMessage());
        assertEquals("the pack's own copy",
                Files.readString(mods.resolve("worlddriver-0.1.0.jar"), StandardCharsets.UTF_8));

        // And a later run with nothing to install must still find it there.
        ModInstall.install(gameDir, List.of(), line -> {});
        assertTrue(Files.isRegularFile(mods.resolve("worlddriver-0.1.0.jar")));
    }

    @Test
    void aRefusedInstallLeavesThePreviousInstallInPlace(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = gameDir.resolve("mods");
        jar(mods, "shipped.jar", "the pack's");
        Path ours = jar(tmp.resolve("build"), "ours.jar", "ours");
        ModInstall.install(gameDir, List.of(ours), line -> {});

        Path clash = jar(tmp.resolve("other"), "shipped.jar", "not the pack's");
        assertThrows(IllegalArgumentException.class,
                () -> ModInstall.install(gameDir, List.of(ours, clash), line -> {}));

        // Nothing swept before the refusal, so the run directory is exactly as the last install left it.
        assertTrue(Files.isRegularFile(mods.resolve("ours.jar")));
        assertEquals("the pack's", Files.readString(mods.resolve("shipped.jar"), StandardCharsets.UTF_8));
    }

    @Test
    void aJarWeInstalledLastTimeIsReplacedByName(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = gameDir.resolve("mods");
        Path first = jar(tmp.resolve("a"), "driver.jar", "first build");
        ModInstall.install(gameDir, List.of(first), line -> {});

        Path second = jar(tmp.resolve("b"), "driver.jar", "second build");
        ModInstall.install(gameDir, List.of(second), line -> {});

        assertEquals("second build", Files.readString(mods.resolve("driver.jar"), StandardCharsets.UTF_8));
    }

    @Test
    void aHandPlacedFrameworkJarIsStillReplaced(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = gameDir.resolve("mods");
        jar(mods, "mc_stagewright-neoforge.jar", "an old framework build");
        Path fresh = jar(tmp.resolve("build"), "mc_stagewright-neoforge.jar", "the new one");

        List<String> log = new ArrayList<>();
        ModInstall.install(gameDir, List.of(fresh), log::add);

        assertEquals("the new one",
                Files.readString(mods.resolve("mc_stagewright-neoforge.jar"), StandardCharsets.UTF_8));
        assertFalse(log.isEmpty());
    }
}
