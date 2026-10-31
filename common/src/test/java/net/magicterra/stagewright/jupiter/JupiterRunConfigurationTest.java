package net.magicterra.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.platform.engine.ConfigurationParameters;

/** The run's defaults give way to what a test author sets: JUnit asks parent parameters before either source. */
class JupiterRunConfigurationTest {
    private static final String PARALLEL = "junit.jupiter.execution.parallel.enabled";

    private static ConfigurationParameters parameters() {
        return JupiterRun.request(List.of(), "junit-jupiter").getConfigurationParameters();
    }

    @Test
    void parallelIsOnByDefault() {
        assertEquals("true", parameters().get(PARALLEL).orElseThrow());
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void aSystemPropertyTurnsItOff() {
        System.setProperty(PARALLEL, "false");
        try {
            assertEquals("false", parameters().get(PARALLEL).orElseThrow());
        } finally {
            System.clearProperty(PARALLEL);
        }
    }

    @Test
    void aPropertiesFileOnTheClassPathTurnsItOff(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("junit-platform.properties"), PARALLEL + "=false\n");
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            thread.setContextClassLoader(loader);
            assertEquals("false", parameters().get(PARALLEL).orElseThrow());
        } finally {
            thread.setContextClassLoader(before);
        }
    }
}
