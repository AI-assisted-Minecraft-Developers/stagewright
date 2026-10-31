package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which directory a scene's clientFiles go into, for each shape of topology. */
class ClientFilesDirectoryTest {
    private static final Path SERVER = Path.of("run-server");

    private static final Path CLIENT = Path.of("run-client");

    private static LaunchSpec.Process process(Path dir) {
        return new LaunchSpec.Process("java", List.of(), "Main", List.of(), dir, Map.of());
    }

    private static LaunchSpec spec(boolean server, LaunchSpec.Process companion) {
        return new LaunchSpec(
                "t", Path.of("."), Path.of("world"), false, 1, 1, server, null, process(SERVER), companion);
    }

    private static SceneFile scene(Map<String, String> clientFiles) {
        return new SceneFile("s", "t", SceneFile.World.QUIET, null, Map.of(), clientFiles, List.of());
    }

    @Test
    void aJoiningClientGetsThem() {
        assertEquals(CLIENT, SceneRun.clientDirectory(spec(true, process(CLIENT)), scene(Map.of("a", "b"))));
    }

    @Test
    void aGameThatIsItselfAClientGetsThem() {
        assertEquals(SERVER, SceneRun.clientDirectory(spec(false, null), scene(Map.of("a", "b"))));
    }

    @Test
    void aDedicatedServerAloneRefusesThem() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> SceneRun.clientDirectory(spec(true, null), scene(Map.of("a", "b"))));
        assertTrue(e.getMessage().contains("clientFiles"), e.getMessage());
    }

    @Test
    void aDedicatedServerAloneWithNoneIsFine() {
        assertEquals(SERVER, SceneRun.clientDirectory(spec(true, null), scene(Map.of())));
    }
}
