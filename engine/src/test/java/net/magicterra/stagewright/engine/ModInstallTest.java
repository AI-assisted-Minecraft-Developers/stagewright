package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What may be taken back out of a {@code mods/} folder this project does not own.
 *
 * <p>The CLI points this at a modpack's server directory or a player's {@code .minecraft}, so every
 * jar in there is the pack's or the player's unless the ledger says an earlier run of ours put it
 * there.
 */
class ModInstallTest {

    private static void jar(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void whatTheLedgerNamesIsTakenBackAndNothingElse(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = gameDir.resolve("mods");
        jar(mods, "mc_stagewright-neoforge-0.1.0+1.21.1.jar", "copied by an old plugin");
        jar(mods, "worlddriver.jar", "copied by an old CLI");
        jar(mods, "jei.jar", "the pack's");
        Files.writeString(mods.resolve(".stagewright-installed"),
                "mc_stagewright-neoforge-0.1.0+1.21.1.jar\nworlddriver.jar\n");

        ModInstall.uninstall(gameDir, line -> {});

        try (Stream<Path> left = Files.list(mods)) {
            assertEquals(List.of(mods.resolve("jei.jar")), left.toList());
        }
    }

    @Test
    void aStageWrightTheLedgerDoesNotNameIsThePacksAndStays(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = gameDir.resolve("mods");
        jar(mods, "mc_stagewright-fabric-0.1.0+1.21.1.jar", "shipped with the pack");

        ModInstall.uninstall(gameDir, line -> {});

        assertTrue(Files.isRegularFile(mods.resolve("mc_stagewright-fabric-0.1.0+1.21.1.jar")));
        assertTrue(ModInstall.frameworkPresent(gameDir));
    }

    @Test
    void aLedgerEntryCannotReachOutsideMods(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = gameDir.resolve("mods");
        jar(gameDir, "options.txt", "the player's");
        jar(mods, "x.jar", "ours");
        Files.writeString(mods.resolve(".stagewright-installed"), "../options.txt\nx.jar\n");

        ModInstall.uninstall(gameDir, line -> {});

        assertTrue(Files.isRegularFile(gameDir.resolve("options.txt")));
        assertFalse(Files.exists(mods.resolve("x.jar")));
    }
}
