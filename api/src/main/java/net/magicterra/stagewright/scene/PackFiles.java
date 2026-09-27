package net.magicterra.stagewright.scene;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a pack's scene files and capability descriptors are read from.
 *
 * <p>By default two directories under {@code config/stagewright/}. A supervisor that must not write
 * into the game directory — the CLI, run against someone's own {@code .minecraft} — names one
 * directory with {@code -Dstagewright.scenesDir} instead, holding both the {@code .js} scenes and the
 * {@code .json} descriptors beside them, and nothing is copied anywhere.
 */
public final class PackFiles {

    /** Repeated in the engine's {@code RunDirectory}, which the CLI reads it from: the two modules
     *  share no classpath, so they agree on the literal. */
    public static final String SCENES_DIR_PROPERTY = "stagewright.scenesDir";

    private static final String DEFAULT_SCENES = "config/stagewright/scenes";
    private static final String DEFAULT_CAPABILITIES = "config/stagewright/capabilities";

    private PackFiles() {}

    /**
     * @throws IllegalStateException when the property names a directory that is not there: the run
     *         would hold none of the scenes it was pointed at and still be judged GREEN
     */
    public static Path scenes() {
        Path named = named();
        if (named != null && !Files.isDirectory(named)) {
            throw new IllegalStateException("-D" + SCENES_DIR_PROPERTY + " names " + named
                    + ", which is not a directory, so none of the scenes meant to run here can");
        }
        return named != null ? named : Path.of(DEFAULT_SCENES);
    }

    public static Path capabilities() {
        Path named = named();
        return named != null ? named : Path.of(DEFAULT_CAPABILITIES);
    }

    /** Read on every call: a loader may set the property after this class has initialised. */
    private static Path named() {
        String value = System.getProperty(SCENES_DIR_PROPERTY, "").trim();
        return value.isEmpty() ? null : Path.of(value);
    }
}
