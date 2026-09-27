package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.engine.Verdict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A {@code --scenes} directory with no scene in it is refused, as the Gradle plugin's is. */
class ScenesDirTest {

    @Test
    void aDirectoryOfDescriptorsAloneHoldsNoScene(@TempDir Path dir) throws IOException {
        assertFalse(Main.holdsASceneFile(dir));
        Files.writeString(dir.resolve("caps.json"), "{}");
        assertFalse(Main.holdsASceneFile(dir));
        Files.writeString(dir.resolve("a.js"), "scene('pack.a', 20, function (s) { });");
        assertTrue(Main.holdsASceneFile(dir));
    }

    @Test
    void anEmptyScenesDirectoryIsRefusedBeforeTheWorldIsReset(@TempDir Path tmp) throws IOException {
        Path server = Files.createDirectories(tmp.resolve("server"));
        Path levelDat = Files.createDirectories(server.resolve("world")).resolve("level.dat");
        Files.writeString(levelDat, "");
        Path scenes = Files.createDirectories(tmp.resolve("scenes"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Main.run(new String[] {
                "--game-dir", server.toString(), "--world", "reset", "--scenes", scenes.toString()}));
        assertTrue(Files.exists(levelDat), "a refused run reset the world anyway");
        assertTrue(e.getMessage().contains("holds no .js"), e.getMessage());
    }

    @Test
    void aRunWhoseHeaderNamesNoScenesDirectoryDidNotReadThem() {
        Path scenes = Path.of("/packs/scenes");
        String unread = Main.scenesNotRead(List.of(Map.of("type", "suite")), scenes);
        assertTrue(unread != null && unread.contains("older than"), unread);
        assertTrue(Main.scenesNotRead(List.of(Map.of("type", "suite", "scenesDir", "/elsewhere")), scenes)
                .contains("instead"));
        assertNull(Main.scenesNotRead(List.of(Map.of("type", "suite", "scenesDir", "/packs/scenes")), scenes));
        assertNull(Main.scenesNotRead(List.of(Map.of("type", "suite")), null));
    }

    @Test
    void aJarThatIgnoredScenesIsEnvNotAGreenRunOfNothing(@TempDir Path tmp) throws IOException {
        Path scenes = Files.createDirectories(tmp.resolve("scenes"));
        Files.writeString(scenes.resolve("a.js"), "scene('pack.a', 20, function (s) { });");
        // What a StageWright older than stagewright.scenesDir writes: a complete run of none of them.
        Path results = Files.writeString(tmp.resolve("results.jsonl"),
                "{\"type\":\"suite\",\"loader\":\"fabric\",\"registered\":[]}\n"
                        + "{\"type\":\"done\",\"scenes\":0}\n");
        List<Map<String, Object>> records = Verdict.parse(results, new ArrayList<>());

        assertEquals(0, Main.verdictOf(records, Map.of()).code(), "the same file with no --scenes is GREEN");
        assertEquals(3, Main.verdictOf(records, Map.of("scenes", scenes.toString())).code());
    }

    @Test
    void aScenesDirectoryMovedDuringTheRunDoesNotCostItsVerdict(@TempDir Path tmp) throws IOException {
        Path gone = tmp.resolve("moved-away");
        Path results = Files.writeString(tmp.resolve("results.jsonl"),
                "{\"type\":\"suite\",\"loader\":\"fabric\",\"registered\":[],\"scenesDir\":\""
                        + gone.toString().replace("\\", "\\\\") + "\"}\n"
                        + "{\"type\":\"done\",\"scenes\":0}\n");
        List<Map<String, Object>> records = Verdict.parse(results, new ArrayList<>());

        assertEquals(0, Main.verdictOf(records, Map.of("scenes", gone.toString())).code());
    }
}
