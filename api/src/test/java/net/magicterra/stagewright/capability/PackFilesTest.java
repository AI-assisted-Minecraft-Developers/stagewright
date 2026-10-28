package net.magicterra.stagewright.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Descriptors come from the config directory, whether or not it exists. */
class PackFilesTest {

    @Test
    void descriptorsComeFromTheConfigDirectory() {
        assertEquals(Path.of("config/stagewright/capabilities"), PackFiles.capabilities());
    }
}
