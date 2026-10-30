package net.magicterra.stagewright.capability;

import java.nio.file.Path;

/**
 * Where a pack's capability descriptors are read from: {@code config/stagewright/capabilities/}, under the game
 * directory.
 */
public final class PackFiles {

    private static final Path CAPABILITIES = Path.of("config/stagewright/capabilities");

    private PackFiles() {}

    /** The directory capability descriptors are read from, relative to the game's working directory. */
    public static Path capabilities() {
        return CAPABILITIES;
    }
}
