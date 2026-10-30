package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What an earlier version's ledger says it put in a {@code mods/} folder this project does not own.
 *
 * <p>The CLI points this at a modpack's server directory or a player's {@code .minecraft}, so every jar in there is the
 * pack's or the player's unless the ledger says an earlier run of ours put it there.
 */
class ModInstallTest {

    private static void jar(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void ledgeredNamesOnlyWhatIsStillThereAndDeletesNothing(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = gameDir.resolve("mods");
        jar(mods, "worlddriver.jar", "whoever put it there last");
        Files.writeString(mods.resolve(".stagewright-installed"), "worlddriver.jar\ngone.jar\n");

        assertEquals(List.of(mods.resolve("worlddriver.jar")), ModInstall.ledgered(gameDir));
        assertTrue(Files.isRegularFile(mods.resolve("worlddriver.jar")));
    }

    @Test
    void aLedgerEntryCannotReachOutsideMods(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = gameDir.resolve("mods");
        jar(gameDir, "options.txt", "the player's");
        jar(mods, "x.jar", "ours");
        Files.writeString(mods.resolve(".stagewright-installed"), "../options.txt\nx.jar\n");

        assertEquals(List.of(mods.resolve("x.jar")), ModInstall.ledgered(gameDir));
    }
}
