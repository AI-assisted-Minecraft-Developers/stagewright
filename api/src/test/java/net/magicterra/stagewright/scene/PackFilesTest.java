package net.magicterra.stagewright.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A scenes directory the supervisor named is read from, or its absence is an error. */
class PackFilesTest {

    @AfterEach
    void unset() {
        System.clearProperty(PackFiles.SCENES_DIR_PROPERTY);
    }

    @Test
    void aNamedDirectoryIsWhereScenesComeFrom(@TempDir Path dir) {
        System.setProperty(PackFiles.SCENES_DIR_PROPERTY, dir.toString());
        assertEquals(dir, PackFiles.scenes());
    }

    @Test
    void aNamedDirectoryThatIsNotThereIsAnErrorNotAnEmptySuite(@TempDir Path tmp) {
        System.setProperty(PackFiles.SCENES_DIR_PROPERTY, tmp.resolve("gone").toString());
        assertThrows(IllegalStateException.class, PackFiles::scenes);
    }

    @Test
    void withNothingNamedTheDefaultIsUsedWhetherOrNotItExists() {
        assertEquals(Path.of("config/stagewright/scenes"), PackFiles.scenes());
    }
}
