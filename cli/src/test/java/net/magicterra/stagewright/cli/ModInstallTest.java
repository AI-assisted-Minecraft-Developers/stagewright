package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --mod} against a pack's real {@code mods/}, which is the one place the CLI installs into.
 *
 * <p>An author passing their own build of a mod the pack already ships, under the same file name, is
 * the ordinary way to hit this — and the pack must come out of it with its jar intact.
 */
class ModInstallTest {

    @Test
    void aModWhoseNameThePackAlreadyShipsIsRefused(@TempDir Path tmp) throws IOException {
        Path gameDir = tmp.resolve("pack");
        Path mods = Files.createDirectories(gameDir.resolve("mods"));
        Files.writeString(mods.resolve("worlddriver-0.1.0+1.21.1.jar"), "shipped", StandardCharsets.UTF_8);
        Path build = Files.createDirectories(tmp.resolve("build"));
        Path mine = Files.writeString(build.resolve("worlddriver-0.1.0+1.21.1.jar"), "mine",
                StandardCharsets.UTF_8);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ModInstall.install(gameDir, "neoforge", List.of(mine), line -> {}));

        assertTrue(refused.getMessage().contains("belongs to the pack"), refused.getMessage());
        assertEquals("shipped", Files.readString(mods.resolve("worlddriver-0.1.0+1.21.1.jar"),
                StandardCharsets.UTF_8));
    }
}
