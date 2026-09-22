package net.magicterra.stagewright.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceConfigurationError;

import net.magicterra.stagewright.contract.Canary;
import net.magicterra.stagewright.scene.Scene;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every way a registry can be refused, turned into a value the run can record — rather than a throw
 * in SERVER_STARTED that leaves no header and reads as "the game never armed".
 */
class SuiteRegistryTest {

    private static Scene scene(String name) {
        return Scene.of(name, 20, ctx -> { });
    }

    @Test
    void aSoundRegistryIsReturnedFiltered() {
        SuiteRegistry.Resolved r = SuiteRegistry.resolve(
                () -> List.of(scene("wd.a"), scene("wd.b"),
                        Scene.canary("canaryMustFail", 20, Canary.MUST_FAIL, ctx -> { })),
                "wd.b");
        assertNull(r.failure());
        assertNull(r.error());
        assertEquals(List.of("wd.b", "canaryMustFail"), r.scenes().stream().map(Scene::name).toList());
        assertEquals(3, r.discovered());
    }

    @Test
    void aDuplicateNameIsARecordedFailure() {
        SuiteRegistry.Resolved r = SuiteRegistry.resolve(
                () -> List.of(scene("pack.smelt"), scene("pack.smelt")), null);
        assertTrue(r.scenes().isEmpty());
        assertTrue(r.error().contains("duplicate scene name 'pack.smelt'"), r.error());
    }

    @Test
    void anIllegalOriginPinIsARecordedFailure() {
        SuiteRegistry.Resolved r = SuiteRegistry.resolve(
                () -> List.of(scene("wd.a").withOriginSlot(3)), null);
        assertTrue(r.error().contains("below floor 1024"), r.error());
    }

    @Test
    void aDiscoveryThatThrowsIsARecordedFailure() {
        // What a .js syntax error, an empty SceneProvider and scene files without Rhino all look like
        // from here: Scenes.all() throwing with the author-facing message.
        SuiteRegistry.Resolved r = SuiteRegistry.resolve(() -> {
            throw new IllegalStateException("scene file pack.js failed to load: missing ; before statement");
        }, null);
        assertEquals("IllegalStateException: scene file pack.js failed to load: missing ; before"
                + " statement", r.error());
    }

    @Test
    void aProviderThatCannotBeLoadedIsARecordedFailure() {
        SuiteRegistry.Resolved r = SuiteRegistry.resolve(() -> {
            throw new ServiceConfigurationError("pack.Scenes: Provider pack.Scenes not found");
        }, null);
        assertTrue(r.error().contains("Provider pack.Scenes not found"), r.error());
    }

    @Test
    void theFailureIsWrittenAsACompleteFileNamingIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("stagewright-results.jsonl");
        new ResultsJsonl(file).writeRegistryFailure("fabric", "wd.*",
                "IllegalStateException: duplicate scene name \"x\"");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(List.of(
                "{\"type\":\"suite\",\"loader\":\"fabric\",\"registered\":[],\"filter\":\"wd.*\","
                        + "\"registryError\":\"IllegalStateException: duplicate scene name \\\"x\\\"\"}",
                "{\"type\":\"done\",\"scenes\":0}"), lines);
    }
}
