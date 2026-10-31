package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The saves a scene's rounds run on, each by the name its tests gave it ({@code @DependsOn(world = …)}). The game
 * always opens the topology's world directory; a round on another world moves the one there onto a shelf under its
 * name, and moves the named one in from the shelf, or starts it new. At the end the scene's first world is put back, so
 * the topology's world is the one the scene began with.
 */
final class SceneWorlds {

    /** The name of the world a scene starts on, as {@code DependsOn.FIRST_WORLD} gives it. */
    static final String FIRST = "first";

    private final Path world;

    private final Path shelf;

    private String current = FIRST;

    /** The worlds of a scene opening {@code world}, kept in {@code shelf}, emptied of an earlier scene's. */
    SceneWorlds(Path world, Path shelf) throws IOException {
        this.world = world;
        this.shelf = shelf;
        SceneMods.deleteTree(shelf);
    }

    /** The name of the world in the topology's directory now. */
    String current() {
        return current;
    }

    /**
     * Put the world named {@code name} where the game opens it: kept from an earlier round, else installed from the
     * save resource {@code from}, else none, so the game makes a new one. Nothing changes for a null name or the
     * current one.
     */
    void switchTo(String name, String from, ClassLoader loader) throws IOException {
        if (name == null || name.equals(current)) {
            return;
        }
        Path aside = shelf.resolve(current);
        SceneMods.deleteTree(aside);
        if (Files.exists(world)) {
            Files.createDirectories(shelf);
            Files.move(world, aside);
        }
        Path kept = shelf.resolve(name);
        if (Files.exists(kept)) {
            Files.createDirectories(world.getParent());
            Files.move(kept, world);
        } else if (from != null && !from.isEmpty()) {
            SceneSetup.installSave(from, loader, world);
        }
        current = name;
    }

    /** The scene is over: its first world back where the topology keeps its world. */
    void restoreFirst() throws IOException {
        switchTo(FIRST, null, null);
    }
}
