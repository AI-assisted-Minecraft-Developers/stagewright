package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What provisioning clears, for every run directory a topology is judged in.
 *
 * <p>A companion client runs in its own directory, which this learns about only through the
 * companion's results path — so anything the companion leaves behind has to be found from there.
 */
class RunDirectoryTest {

    @Test
    void theCompanionsEndpointDescriptorIsDeleted(@TempDir Path tmp) throws IOException {
        Path gameDir = Files.createDirectories(tmp.resolve("server"));
        Path companionDir = Files.createDirectories(tmp.resolve("client"));
        Path companionResults = companionDir.resolve("stagewright-client.jsonl");
        Path stale = Files.writeString(companionDir.resolve(RunDirectory.ENDPOINT_FILE),
                "{\"rpcHost\":\"127.0.0.1\",\"rpcPort\":39999}");

        RunDirectory.provision(gameDir,
                List.of(gameDir.resolve(RunDirectory.DEFAULT_RESULTS_FILE), companionResults),
                false, null, line -> {});

        assertFalse(Files.exists(stale), "the companion's stale endpoint descriptor survived provision");
    }

    @Test
    void aSceneScriptsDirectoryWithNoSceneFileIsRefused(@TempDir Path tmp) throws IOException {
        Path gameDir = Files.createDirectories(tmp.resolve("server"));
        Path source = Files.createDirectories(tmp.resolve("scenes"));
        Files.writeString(source.resolve("pack-capabilities.json"), "{}");

        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> RunDirectory.provision(gameDir,
                        List.of(gameDir.resolve(RunDirectory.DEFAULT_RESULTS_FILE)), false, source,
                        line -> {}));
        assertTrue(e.getMessage().contains("no .js"), e.getMessage());
    }

    @Test
    void theRunDirectorysOwnDescriptorIsDeleted(@TempDir Path tmp) throws IOException {
        Path gameDir = Files.createDirectories(tmp.resolve("server"));
        Path stale = Files.writeString(gameDir.resolve(RunDirectory.ENDPOINT_FILE), "{}");

        RunDirectory.provision(gameDir, List.of(gameDir.resolve("renamed.jsonl")), false, null,
                line -> {});

        assertFalse(Files.exists(stale));
    }
}
