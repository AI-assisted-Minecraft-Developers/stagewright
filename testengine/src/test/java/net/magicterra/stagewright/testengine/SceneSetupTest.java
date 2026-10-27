package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A scene's save and files, put into a game directory and taken out again. */
class SceneSetupTest {

    @TempDir
    Path temp;

    private ClassLoader classPath() throws IOException {
        return new URLClassLoader(new URL[] {temp.resolve("cp").toUri().toURL()}, null);
    }

    private void zip(String resource, String... entries) throws IOException {
        Path file = temp.resolve("cp").resolve(resource);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String e : entries) {
                zip.putNextEntry(new ZipEntry(e));
                zip.write(e.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    @Test
    void aZipOfTheWorldFolderIsUnpackedFromInsideIt() throws IOException {
        zip("stagewright/worlds/old.zip", "MyWorld/level.dat", "MyWorld/region/r.0.0.mca", "README.txt");
        Path world = temp.resolve("game/world");
        SceneSetup.installSave("worlds/old.zip", classPath(), world);
        assertTrue(Files.isRegularFile(world.resolve("level.dat")));
        assertTrue(Files.isRegularFile(world.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(world.resolve("README.txt")), "outside the world folder");
    }

    @Test
    void aZipOfTheWorldsContentsIsUnpackedAsIs() throws IOException {
        zip("stagewright/flat.zip", "level.dat", "data/raids.dat");
        Path world = temp.resolve("game/saves/StageWright");
        SceneSetup.installSave("flat.zip", classPath(), world);
        assertTrue(Files.isRegularFile(world.resolve("data/raids.dat")));
    }

    @Test
    void aDirectoryOnTheClassPathIsCopied() throws IOException {
        Path save = Files.createDirectories(temp.resolve("cp/stagewright/worlds/dir"));
        Files.writeString(save.resolve("level.dat"), "x");
        Path world = temp.resolve("game/world");
        SceneSetup.installSave("worlds/dir", classPath(), world);
        assertEquals("x", Files.readString(world.resolve("level.dat")));
    }

    @Test
    void aZipWithNoLevelDatIsNoSave() throws IOException {
        zip("stagewright/junk.zip", "a.txt");
        String message = assertThrows(IllegalStateException.class,
                () -> SceneSetup.installSave("junk.zip", classPath(), temp.resolve("w"))).getMessage();
        assertTrue(message.contains("level.dat"), message);
    }

    @Test
    void filesArePutBackAsTheyWere(@TempDir Path game) throws IOException {
        Path existing = Files.createDirectories(game.resolve("config")).resolve("a.toml");
        Files.writeString(existing, "old");
        SceneSetup.Restore restore = SceneSetup.writeFiles(game, Map.of("config/a.toml", "new",
                "config/deep/b.toml", "made"));
        assertEquals("new", Files.readString(existing));
        assertEquals("made", Files.readString(game.resolve("config/deep/b.toml")));
        restore.run();
        assertEquals("old", Files.readString(existing));
        assertFalse(Files.exists(game.resolve("config/deep")), "a directory the scene made is gone again");
    }

    @Test
    void aDirectoryTwoFilesMadeIsGoneAgainWhicheverCameFirst(@TempDir Path game) throws IOException {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("packs/probe/pack.mcmeta", "{}");
        files.put("packs/probe/data/probe/function/hello.mcfunction", "return 1");
        SceneSetup.writeFiles(game, files).run();
        assertFalse(Files.exists(game.resolve("packs")), "left behind: a directory the scene made");
    }
}
