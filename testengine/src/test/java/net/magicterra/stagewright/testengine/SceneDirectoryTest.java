package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A scene's own copy of its topology's directory: what is copied, linked and left out, and what is kept after. */
class SceneDirectoryTest {

    @TempDir
    Path temp;

    private Path topology;

    @BeforeEach
    void aTopologyDirectory() throws IOException {
        topology = Files.createDirectories(temp.resolve("run"));
        Files.writeString(topology.resolve("server.properties"), "motd=x\n");
        Files.writeString(topology.resolve("server.jar"), "jar");
        Files.writeString(Files.createDirectories(topology.resolve("config")).resolve("mod.toml"), "a=1");
        Files.writeString(Files.createDirectories(topology.resolve("mods")).resolve("m.jar"), "m");
        Files.writeString(
                Files.createDirectories(topology.resolve("libraries/x")).resolve("args.txt"), "-Dx");
        Files.writeString(Files.createDirectories(topology.resolve("world")).resolve("level.dat"), "w");
        Files.createDirectories(topology.resolve("saves/StageWright"));
        Files.createDirectories(topology.resolve("logs"));
        Files.createDirectories(topology.resolve("stagewright/runs/other"));
    }

    @Test
    void settingsAreCopiedTheRestLinkedAndWorldsAndArtifactsLeftOut() throws IOException {
        Path dir = SceneDirectory.create(topology, "s", null, false);
        assertEquals(topology.resolve("stagewright/runs/s"), dir);
        assertFalse(Files.isSymbolicLink(dir.resolve("server.properties")));
        assertFalse(Files.isSymbolicLink(dir.resolve("config")));
        assertEquals("a=1", Files.readString(dir.resolve("config/mod.toml")));
        assertTrue(Files.isSymbolicLink(dir.resolve("server.jar")));
        assertEquals("-Dx", Files.readString(dir.resolve("libraries/x/args.txt")), "a relative path still resolves");
        for (String gone : List.of("world", "saves", "logs", "stagewright")) {
            assertFalse(Files.exists(dir.resolve(gone)), gone);
        }
    }

    @Test
    void eachLinkedDirectoryIsARealOneALoaderCanListWithoutFollowingLinks() throws IOException {
        Path dir = SceneDirectory.create(topology, "s", null, false);
        for (String linked : List.of("mods", "libraries")) {
            assertFalse(Files.isSymbolicLink(dir.resolve(linked)), linked);
            assertTrue(Files.isDirectory(dir.resolve(linked)), linked);
        }
        assertTrue(Files.isSymbolicLink(dir.resolve("mods/m.jar")));
        assertTrue(Files.isSymbolicLink(dir.resolve("libraries/x")));
        // How FML finds its launch plugins: a walk one deep that does not follow a link it starts at.
        try (Stream<Path> walk = Files.walk(dir.resolve("mods"), 1)) {
            assertEquals(
                    List.of(dir.resolve("mods/m.jar")),
                    walk.filter(p -> !p.equals(dir.resolve("mods"))).toList());
        }
        assertEquals("m", Files.readString(dir.resolve("mods/m.jar")));
    }

    @Test
    void replacingThePacksSceneFilesLeavesTheRunsAlone() throws IOException {
        Files.writeString(
                Files.createDirectories(topology.resolve("stagewright/scenes")).resolve("s.yaml"), "x");
        Path dir = SceneDirectory.create(topology, "s", null, false);
        Files.writeString(Files.createDirectories(dir.resolve("stagewright")).resolve("store.json"), "{}");
        SceneMods.deleteTree(topology.resolve("stagewright/scenes"));
        assertTrue(Files.isRegularFile(dir.resolve("stagewright/store.json")));
    }

    @Test
    void aLinkedSettingsDirectoryIsCopiedFromWhereItPoints() throws IOException {
        Path pack = Files.createDirectories(temp.resolve("pack/config/deep"));
        Files.writeString(pack.resolve("x.toml"), "x=1");
        Path client = Files.createDirectories(temp.resolve("client"));
        Files.createSymbolicLink(client.resolve("config"), temp.resolve("pack/config"));
        Path dir = SceneDirectory.create(client, "s", null, false);
        assertFalse(Files.isSymbolicLink(dir.resolve("config")));
        assertEquals("x=1", Files.readString(dir.resolve("config/deep/x.toml")));
    }

    @Test
    void aTopologyKeepingItsWorldLinksIt() throws IOException {
        Path dir = SceneDirectory.create(topology, "s", "world", true);
        assertTrue(Files.isSymbolicLink(dir.resolve("world")));
        assertTrue(Files.isSymbolicLink(dir.resolve("saves")));
    }

    @Test
    void aKeptWorldIsLinkedBeforeAnyScenesMadeIt() throws IOException {
        Path empty = Files.createDirectories(temp.resolve("empty"));
        Path dir = SceneDirectory.create(empty, "s", "world", true);
        Files.writeString(dir.resolve("world/level.dat"), "first");
        assertEquals("first", Files.readString(empty.resolve("world/level.dat")), "the next scene opens it");
    }

    @Test
    void aWorldUnderAnotherNameIsLeftOutToo() throws IOException {
        Files.createDirectories(topology.resolve("lab"));
        Path dir = SceneDirectory.create(topology, "s", "lab", false);
        assertFalse(Files.exists(dir.resolve("lab")));
    }

    @Test
    void writingTheSceneChangesNothingShared() throws IOException {
        Path dir = SceneDirectory.create(topology, "s", null, false);
        Files.writeString(dir.resolve("config/mod.toml"), "a=2");
        Files.writeString(dir.resolve("server.properties"), "motd=y\n");
        SceneSetup.writeFiles(dir, Map.of("mods/extra.txt", "scene", "server.jar", "replaced"));
        assertEquals("a=1", Files.readString(topology.resolve("config/mod.toml")));
        assertEquals("motd=x\n", Files.readString(topology.resolve("server.properties")));
        assertFalse(Files.exists(topology.resolve("mods/extra.txt")));
        assertEquals("jar", Files.readString(topology.resolve("server.jar")));
        assertEquals("m", Files.readString(dir.resolve("mods/m.jar")), "the shared jars still show through");
    }

    @Test
    void aPassedSceneKeepsItsArtifactsOnlyAndNothingSharedIsDeleted() throws IOException {
        Path dir = SceneDirectory.create(topology, "s", "world", true);
        Files.writeString(
                Files.createDirectories(dir.resolve("stagewright/logs")).resolve("s-game.log"), "log");
        Files.createDirectories(dir.resolve("stagewright/world/region"));
        Files.createDirectories(dir.resolve("stagewright/saves/first"));
        SceneDirectory.finish(dir, true);
        try (var artifacts = Files.list(dir.resolve("stagewright"))) {
            assertEquals(
                    List.of("logs"),
                    artifacts.map(p -> p.getFileName().toString()).toList());
        }
        try (var left = Files.list(dir)) {
            assertEquals(
                    List.of("stagewright"),
                    left.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals("w", Files.readString(topology.resolve("world/level.dat")));
        assertEquals("m", Files.readString(topology.resolve("mods/m.jar")));
    }

    @Test
    void aPassedSceneWithNoArtifactsLeavesNoDirectory() throws IOException {
        Path dir = SceneDirectory.create(topology, "s", null, false);
        SceneDirectory.finish(dir, true);
        assertFalse(Files.exists(dir));
    }

    @Test
    void aFailedSceneKeepsEverythingUntilItRunsAgain() throws IOException {
        Path dir = SceneDirectory.create(topology, "s", null, false);
        Files.writeString(Files.createDirectories(dir.resolve("world")).resolve("level.dat"), "scene");
        SceneDirectory.finish(dir, false);
        assertEquals("scene", Files.readString(dir.resolve("world/level.dat")));
        SceneDirectory.create(topology, "s", null, false);
        assertFalse(Files.exists(dir.resolve("world")));
        assertEquals("m", Files.readString(topology.resolve("mods/m.jar")));
    }

    @Test
    void theLaunchMovesIntoTheSceneDirectories() throws IOException {
        Path client = Files.createDirectories(temp.resolve("client"));
        LaunchSpec.Process primary = new LaunchSpec.Process(
                "java", List.of(), "M", List.of("--gameDir", ".", "--nogui"), topology, Map.of());
        LaunchSpec.Process companion = new LaunchSpec.Process(
                "java", List.of(), "C", List.of("--gameDir", client.toString()), client, Map.of());
        LaunchSpec spec = new LaunchSpec(
                "t",
                topology,
                topology.resolve("saves/StageWright"),
                true,
                1,
                1,
                false,
                LaunchSpec.Leaks.FAIL,
                primary,
                companion);
        LaunchSpec moved = spec.inSceneDirectories("s");
        Path dir = SceneDirectory.of(topology, "s");
        assertEquals(dir, moved.gameDirectory());
        assertEquals(dir.resolve("saves/StageWright"), moved.worldDirectory());
        assertEquals(dir, moved.primary().workingDirectory());
        assertEquals(
                List.of("--gameDir", dir.toString(), "--nogui"), moved.primary().args());
        Path clientDir = SceneDirectory.of(client, "s");
        assertEquals(clientDir, moved.companion().workingDirectory());
        assertEquals(
                List.of("--gameDir", clientDir.toString()), moved.companion().args());
        assertEquals(List.of(dir, clientDir), moved.directories());
    }
}
