package net.magicterra.stagewright.capability;

import java.nio.file.Path;

/**
 * Where a pack's capability descriptors are read from: {@code config/stagewright/capabilities/} by default. A
 * supervisor that must not write into the game directory — the CLI, run against someone's own {@code .minecraft} —
 * names a directory with {@code -Dstagewright.scenesDir} instead, and the descriptors are read from there.
 */
public final class PackFiles {

    /** Nothing in this repository sets it any more; the read stays until the old results path is removed. */
    public static final String SCENES_DIR_PROPERTY = "stagewright.scenesDir";

    private static final String DEFAULT_CAPABILITIES = "config/stagewright/capabilities";

    private PackFiles() {}

    public static Path capabilities() {
        // Read on every call: a loader may set the property after this class has initialised.
        String value = System.getProperty(SCENES_DIR_PROPERTY, "").trim();
        return value.isEmpty() ? Path.of(DEFAULT_CAPABILITIES) : Path.of(value);
    }
}
