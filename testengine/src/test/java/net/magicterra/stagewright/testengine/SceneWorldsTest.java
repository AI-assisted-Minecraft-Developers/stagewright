package net.magicterra.stagewright.testengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A scene's rounds moving from one named save to another, and back. */
class SceneWorldsTest {

    @TempDir
    Path temp;

    private Path world() {
        return temp.resolve("game/world");
    }

    private void save(Path dir, String marker) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("level.dat"), marker);
    }

    private String marker() throws IOException {
        return Files.readString(world().resolve("level.dat"));
    }

    @Test
    void aNewNameLeavesNoWorldSoTheGameMakesOne() throws IOException {
        save(world(), "first");
        SceneWorlds worlds = new SceneWorlds(world(), temp.resolve("game/stagewright/saves"));
        worlds.switchTo("second", "", null);
        assertFalse(Files.exists(world()));
        assertEquals("second", worlds.current());
    }

    @Test
    void anEarlierWorldComesBackAsItsLastRoundLeftIt() throws IOException {
        save(world(), "first");
        SceneWorlds worlds = new SceneWorlds(world(), temp.resolve("game/stagewright/saves"));
        worlds.switchTo("second", "", null);
        save(world(), "second, played");
        worlds.switchTo(SceneWorlds.FIRST, "", null);
        assertEquals("first", marker());
        worlds.switchTo("second", "", null);
        assertEquals("second, played", marker());
    }

    @Test
    void theSameOrNoNameChangesNothing() throws IOException {
        save(world(), "first");
        SceneWorlds worlds = new SceneWorlds(world(), temp.resolve("game/stagewright/saves"));
        worlds.switchTo(null, "", null);
        worlds.switchTo(SceneWorlds.FIRST, "", null);
        assertEquals("first", marker());
    }

    @Test
    void theFirstWorldIsPutBackAtTheEnd() throws IOException {
        save(world(), "first");
        SceneWorlds worlds = new SceneWorlds(world(), temp.resolve("game/stagewright/saves"));
        worlds.switchTo("second", "", null);
        save(world(), "second");
        worlds.restoreFirst();
        assertEquals("first", marker());
        assertTrue(Files.isRegularFile(temp.resolve("game/stagewright/saves/second/level.dat")));
    }

    @Test
    void aWorldNewToTheSceneStartsFromItsSave() throws IOException {
        Path file = temp.resolve("cp/stagewright/worlds/old.zip");
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("level.dat"));
            zip.write("old".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        save(world(), "first");
        SceneWorlds worlds = new SceneWorlds(world(), temp.resolve("game/stagewright/saves"));
        try (URLClassLoader cp =
                new URLClassLoader(new URL[] {temp.resolve("cp").toUri().toURL()}, null)) {
            worlds.switchTo("old", "worlds/old.zip", cp);
        }
        assertEquals("old", marker());
    }

    @Test
    void anEarlierScenesShelfIsEmptied() throws IOException {
        save(temp.resolve("game/stagewright/saves/second"), "an earlier scene's");
        save(world(), "first");
        SceneWorlds worlds = new SceneWorlds(world(), temp.resolve("game/stagewright/saves"));
        worlds.switchTo("second", "", null);
        assertFalse(Files.exists(world()));
    }
}
