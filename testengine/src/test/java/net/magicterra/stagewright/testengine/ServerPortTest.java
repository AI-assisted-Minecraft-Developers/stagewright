package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
    void thePlaceholderIsReplacedByThePortAndNothingElseChanges() {
        LaunchSpec spec = spec(List.of("--port", LaunchSpec.FREE_PORT, "--nogui"));
        assertTrue(spec.wantsFreePort());
        LaunchSpec given = spec.withServerPort(40123);
        assertEquals(List.of("--port", "40123", "--nogui"), given.primary().args());
        assertFalse(given.wantsFreePort());
        assertEquals(spec.primary().workingDirectory(), given.primary().workingDirectory());
    }

    @Test
    void aFixedPortIsLeftAlone() {
        assertFalse(spec(List.of("--port", "25700")).wantsFreePort());
    }

    @Test
    void aFreePortIsOneNothingListensOn() throws IOException {
        int port = SceneRun.freePort();
        assertTrue(port > 0 && port < 65_536, String.valueOf(port));
    }
}
