package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which loader a pack directory boots.
 *
 * <p>Every case here is a shape a real pack directory has: a folder under {@code libraries/} holding
 * every loader the pack has ever had installed, and one launch script the installer wrote naming the
 * one it actually boots.
 */
class GameLaunchTest {

    @Test
    void numbersCompareAsNumbersNotAsText() {
        // The bug a string compare has that nobody notices until a loader passes .99: '9' > '1'.
        assertTrue(GameLaunch.compareVersions("21.1.117", "21.1.9") > 0);
        assertTrue(GameLaunch.compareVersions("21.1.248", "21.1.99") > 0);
        assertTrue(GameLaunch.compareVersions("21.5.34", "21.1.248") > 0);
        assertEquals(0, GameLaunch.compareVersions("21.1.248", "21.1.248"));
    }

    @Test
    void aPreReleaseSortsBelowTheVersionItPrecedes() {
        assertTrue(GameLaunch.compareVersions("21.5.34", "21.5.34-beta") > 0);
        assertTrue(GameLaunch.compareVersions("21.5.34-rc1", "21.5.34-beta") > 0);
        // …and a trailing NUMBER is the opposite case: more segments means a later patch.
        assertTrue(GameLaunch.compareVersions("21.5.34.1", "21.5.34") > 0);
    }

    @Test
    void theInstallersRunScriptDecides(@TempDir Path gameDir) throws IOException {
        installLoader(gameDir, "21.1.248");
        installLoader(gameDir, "21.5.34-beta");
        Files.writeString(gameDir.resolve("run.sh"),
                "java @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.248/unix_args.txt \"$@\"\n");

        List<String> log = new ArrayList<>();
        List<String> command = GameLaunch.detect(gameDir, "java", log::add);

        // The beta is newer by every ordering rule and is NOT what this pack boots.
        assertTrue(command.stream().anyMatch(a -> a.contains("/21.1.248/")), command.toString());
        assertTrue(command.stream().noneMatch(a -> a.contains("21.5.34")), command.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("neoforge 21.1.248")), log.toString());
    }

    @Test
    void withNoRunScriptTheNewestIsChosenAndSaidToBeAGuess(@TempDir Path gameDir) throws IOException {
        installLoader(gameDir, "21.1.9");
        installLoader(gameDir, "21.1.117");

        List<String> log = new ArrayList<>();
        List<String> command = GameLaunch.detect(gameDir, "java", log::add);

        assertTrue(command.stream().anyMatch(a -> a.contains("/21.1.117/")), command.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("neoforge 21.1.117")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("--launch")), log.toString());
    }

    @Test
    void aWindowsScriptIsReadOnAPlatformThatCannotRunIt(@TempDir Path gameDir) throws IOException {
        installLoader(gameDir, "21.1.190");
        installLoader(gameDir, "21.5.34-beta");
        // Backslashes and CRLF, exactly as the NeoForge installer writes them.
        Files.writeString(gameDir.resolve("run.bat"),
                "java @user_jvm_args.txt @libraries\\net\\neoforged\\neoforge\\21.1.190\\win_args.txt %*\r\n");

        List<String> command = GameLaunch.detect(gameDir, "java", line -> { });

        assertTrue(command.stream().anyMatch(a -> a.contains("/21.1.190/")), command.toString());
    }

    /** A version directory holding both platforms' argument files, as an installer leaves it. */
    private static void installLoader(Path gameDir, String version) throws IOException {
        Path base = gameDir.resolve("libraries/net/neoforged/neoforge").resolve(version);
        Files.createDirectories(base);
        Files.writeString(base.resolve("unix_args.txt"), "-cp x\nnet.neoforged.Main\n");
        Files.writeString(base.resolve("win_args.txt"), "-cp x\r\nnet.neoforged.Main\r\n");
    }
}
