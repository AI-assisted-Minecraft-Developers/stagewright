package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** NeoForge's early window is turned off in a scene's client, and nowhere else. */
class EarlyWindowTest {

    @TempDir
    Path dir;

    private LaunchSpec.Process process(List<String> args) {
        return new LaunchSpec.Process("java", List.of(), "Main", args, dir, Map.of());
    }

    @Test
    void aClientTargetInAnArgumentFileIsNeoForgesClient() throws IOException {
        Path args = Files.writeString(
                dir.resolve("program.txt"),
                "# Main Class\ncpw.mods.bootstraplauncher.BootstrapLauncher\n\n--launchTarget\nforgeclientdev\n--gameDir\n.\n");
        assertTrue(EarlyWindow.neoForgeClient(process(List.of("@" + args))));
        assertTrue(EarlyWindow.neoForgeClient(process(List.of("--launchTarget", "forgeclient"))));
    }

    @Test
    void aServerTargetOrAFabricClientIsNot() throws IOException {
        assertFalse(EarlyWindow.neoForgeClient(process(List.of("--launchTarget", "forgeserverdev"))));
        assertFalse(EarlyWindow.neoForgeClient(process(List.of("--gameDir", ".", "--username", "Player"))));
    }

    @Test
    void noSettingsFileGetsOneWithTheWindowOff() throws IOException {
        EarlyWindow.turnOff(dir);
        assertEquals("earlyWindowControl = false\n", Files.readString(dir.resolve(EarlyWindow.CONFIG)));
    }

    @Test
    void aCopiedSettingsFileKeepsEveryOtherLine() throws IOException {
        Path file = dir.resolve(EarlyWindow.CONFIG);
        Files.createDirectories(file.getParent());
        Files.writeString(
                file, "maxThreads = -1\nearlyWindowControl = true\nearlyWindowProvider = \"fmlearlywindow\"\n");
        EarlyWindow.turnOff(dir);
        assertEquals(
                "maxThreads = -1\nearlyWindowControl = false\nearlyWindowProvider = \"fmlearlywindow\"\n",
                Files.readString(file));
    }

    @Test
    void aSettingsFileWithoutTheKeyGetsItAtTheTop() throws IOException {
        Path file = dir.resolve(EarlyWindow.CONFIG);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "maxThreads = -1\n");
        EarlyWindow.turnOff(dir);
        assertEquals("earlyWindowControl = false\nmaxThreads = -1\n", Files.readString(file));
    }
}
