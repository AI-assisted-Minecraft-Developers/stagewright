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

/** A server given a free port as its scene starts, unless its suite fixed one. */
class ServerPortTest {

    @TempDir
    Path temp;

    private LaunchSpec spec(List<String> args) {
        LaunchSpec.Process primary = new LaunchSpec.Process("java", List.of(), "server", args, temp, Map.of());
        return new LaunchSpec(
                "dedicatedServer",
                temp,
                temp.resolve("world"),
                true,
                20,
                0,
                true,
                LaunchSpec.Leaks.FAIL,
                primary,
                null);
    }

    @Test
    void thePlaceholderIsReplacedByThePortAndNothingElseChanges() throws IOException {
        LaunchSpec spec = spec(List.of("--port", LaunchSpec.FREE_PORT, "--nogui"));
        assertTrue(spec.wantsFreePort());
        LaunchSpec given = spec.withServerPort(40123, temp.resolve("scratch"));
        assertEquals(List.of("--port", "40123", "--nogui"), given.primary().args());
        assertFalse(given.wantsFreePort());
        assertEquals(spec.primary().workingDirectory(), given.primary().workingDirectory());
    }

    @Test
    void inAnArgumentFileACopyCarriesThePort() throws IOException {
        // As ModDevGradle writes a run's program arguments.
        Path file = temp.resolve("RunProgramArgs.txt");
        String original = "# Main Class\nMain\n\n# User Supplied Program Arguments\n--port\n" + LaunchSpec.FREE_PORT;
        Files.writeString(file, original);
        LaunchSpec spec = spec(List.of("@" + file));
        assertTrue(spec.wantsFreePort());
        LaunchSpec given = spec.withServerPort(40123, temp.resolve("scratch"));
        String arg = given.primary().args().getFirst();
        Path copy = Path.of(arg.substring(1));
        assertEquals(temp.resolve("scratch").resolve("RunProgramArgs.txt").toAbsolutePath(), copy);
        assertEquals(original.replace(LaunchSpec.FREE_PORT, "40123"), Files.readString(copy));
        assertEquals(original, Files.readString(file), "the build's own file is left as it was");
        assertFalse(given.wantsFreePort());
    }

    @Test
    void aFixedPortIsLeftAlone() throws IOException {
        assertFalse(spec(List.of("--port", "25700")).wantsFreePort());
        Path file = temp.resolve("args.txt");
        Files.writeString(file, "--port\n25700\n");
        assertFalse(spec(List.of("@" + file)).wantsFreePort());
    }

    @Test
    void aFreePortIsOneNothingListensOn() throws IOException {
        int port = SceneRun.freePort();
        assertTrue(port > 0 && port < 65_536, String.valueOf(port));
    }
}
