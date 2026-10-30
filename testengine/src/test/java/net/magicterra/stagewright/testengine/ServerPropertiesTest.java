package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link SceneRun#writeServerProperties}: what lands in {@code server.properties} before the game starts. */
class ServerPropertiesTest {

    @TempDir
    Path temp;

    private SceneFile.World world(String worldYaml) throws IOException {
        Path file = temp.resolve("cp/stagewright/scenes/x.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "topology: t\nworld:\n" + worldYaml);
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {temp.resolve("cp").toUri().toURL()}, null)) {
            return SceneFile.load("x", loader).world();
        }
    }

    private static Properties read(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            p.load(in);
        }
        return p;
    }

    @Test
    void keepsUnrelatedKeysAndWritesTheCreationKeys() throws IOException {
        Path file = temp.resolve("server.properties");
        Files.writeString(file, "server-port=25601\nlevel-seed=old\n");
        SceneRun.writeServerProperties(
                file,
                world("  seed: 5471\n  preset: minecraft:flat\n  structures: false\n"
                        + "  gamemode: creative\n  difficulty: hard\n"));
        Properties p = read(file);
        assertEquals("25601", p.getProperty("server-port"));
        assertEquals("5471", p.getProperty("level-seed"));
        assertEquals("minecraft:flat", p.getProperty("level-type"));
        assertEquals("false", p.getProperty("generate-structures"));
        assertEquals("creative", p.getProperty("gamemode"));
        assertEquals("hard", p.getProperty("difficulty"));
    }

    @Test
    void createsTheFileWhenThereWasNone() throws IOException {
        Path file = temp.resolve("deep/er/server.properties");
        SceneRun.writeServerProperties(file, world("  seed: 7\n"));
        assertEquals("7", read(file).getProperty("level-seed"));
    }

    @Test
    void enabledPacksJoinTheExistingList() throws IOException {
        Path file = temp.resolve("server.properties");
        Files.writeString(file, "initial-enabled-packs=vanilla,extra\n");
        SceneRun.writeServerProperties(file, world("  datapacks: { enable: [a, extra, b] }\n"));
        assertEquals("vanilla,extra,a,b", read(file).getProperty("initial-enabled-packs"));
    }

    @Test
    void enabledPacksJoinVanillaWhenTheFileListsNone() throws IOException {
        Path file = temp.resolve("server.properties");
        Files.writeString(file, "server-port=1\n");
        SceneRun.writeServerProperties(file, world("  datapacks: { enable: [a] }\n"));
        assertEquals("vanilla,a", read(file).getProperty("initial-enabled-packs"));
    }

    @Test
    void disabledPacksJoinTheExistingList() throws IOException {
        Path file = temp.resolve("server.properties");
        Files.writeString(file, "initial-disabled-packs=x\n");
        SceneRun.writeServerProperties(file, world("  datapacks: { disable: [y] }\n"));
        assertEquals("x,y", read(file).getProperty("initial-disabled-packs"));
    }

    @Test
    void disabledPacksStandAloneWhenTheFileListsNone() throws IOException {
        Path file = temp.resolve("server.properties");
        Files.writeString(file, "server-port=1\n");
        SceneRun.writeServerProperties(file, world("  datapacks: { disable: [y] }\n"));
        assertEquals("y", read(file).getProperty("initial-disabled-packs"));
    }

    @Test
    void doesNothingWhenNothingAppliesAtCreation() throws IOException {
        Path existing = temp.resolve("server.properties");
        String content = "server-port=1\n# kept as is\n";
        Files.writeString(existing, content);
        SceneRun.writeServerProperties(existing, world("  gamerules: { doFireTick: false }\n"));
        assertEquals(content, Files.readString(existing));

        Path absent = temp.resolve("none/server.properties");
        SceneRun.writeServerProperties(absent, SceneFile.World.QUIET);
        assertFalse(Files.exists(absent));
    }
}
