package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Path companionResults = companionDir.resolve(RunDirectory.CLIENT_RESULTS_FILE);
        Files.createDirectories(companionResults.getParent());
        Path stale = Files.writeString(companionResults.resolveSibling(RunDirectory.ENDPOINT_NAME),
                "{\"rpcHost\":\"127.0.0.1\",\"rpcPort\":39999}");

        RunDirectory.provision(gameDir,
                List.of(gameDir.resolve(RunDirectory.DEFAULT_RESULTS_FILE), companionResults),
                false, true, line -> {});

        assertFalse(Files.exists(stale), "the companion's stale endpoint descriptor survived provision");
    }

    @Test
    void aSceneScriptsDirectoryWithNoSceneFileIsRefused(@TempDir Path tmp) throws IOException {
        Path gameDir = Files.createDirectories(tmp.resolve("server"));
        Path source = Files.createDirectories(tmp.resolve("scenes"));
        Files.writeString(source.resolve("pack-capabilities.json"), "{}");

        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> RunDirectory.installAuthoredContent(gameDir, source, line -> {}));
        assertTrue(e.getMessage().contains("no .js"), e.getMessage());
    }

    @Test
    void theRunDirectorysOwnDescriptorIsDeleted(@TempDir Path tmp) throws IOException {
        Path gameDir = Files.createDirectories(tmp.resolve("server"));
        Files.createDirectories(gameDir.resolve(RunDirectory.ARTIFACT_DIR));
        Path stale = Files.writeString(gameDir.resolve(RunDirectory.ENDPOINT_FILE), "{}");

        RunDirectory.provision(gameDir, List.of(gameDir.resolve("renamed.jsonl")), false, true,
                line -> {});

        assertFalse(Files.exists(stale));
    }

    @Test
    void aClientDirectoryGetsNoServerFilesAndKeepsItsOptions(@TempDir Path tmp) throws IOException {
        Path gameDir = Files.createDirectories(tmp.resolve(".minecraft"));
        Path options = Files.writeString(gameDir.resolve("options.txt"), "key_key.jump:key.keyboard.space\n");

        RunDirectory.provision(gameDir, List.of(gameDir.resolve(RunDirectory.DEFAULT_RESULTS_FILE)),
                false, false, line -> {});

        assertEquals("key_key.jump:key.keyboard.space\n", Files.readString(options));
        assertFalse(Files.exists(gameDir.resolve("eula.txt")));
        assertFalse(Files.exists(gameDir.resolve("server.properties")));
    }
}
