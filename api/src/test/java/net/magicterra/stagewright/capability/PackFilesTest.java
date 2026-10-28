package net.magicterra.stagewright.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A directory the supervisor named is where descriptors come from; otherwise the config default. */
class PackFilesTest {

    @AfterEach
    void unset() {
        System.clearProperty(PackFiles.SCENES_DIR_PROPERTY);
    }

    @Test
    void aNamedDirectoryIsWhereDescriptorsComeFrom(@TempDir Path dir) {
        System.setProperty(PackFiles.SCENES_DIR_PROPERTY, dir.toString());
        assertEquals(dir, PackFiles.capabilities());
    }

    @Test
    void withNothingNamedTheDefaultIsUsedWhetherOrNotItExists() {
        assertEquals(Path.of("config/stagewright/capabilities"), PackFiles.capabilities());
    }
}
