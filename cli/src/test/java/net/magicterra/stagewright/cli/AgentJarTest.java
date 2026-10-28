package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import net.magicterra.stagewright.engine.supervise.AgentJar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentJarTest {

    @Test
    void theAgentRidesInsideTheRunnerAndIsWrittenWhereAGameCanLoadItFrom(@TempDir Path artifacts) throws Exception {
        Path jar = AgentJar.stage(Main.class, artifacts.resolve("stagewright"));
        assertEquals(artifacts.resolve("stagewright").resolve(AgentJar.FILE), jar);
        try (JarFile file = new JarFile(jar.toFile())) {
            assertEquals("net.magicterra.stagewright.agent.Agent",
                    file.getManifest().getMainAttributes().getValue("Premain-Class"));
        }
        // A second run replaces it rather than failing on the file left by the first.
        Files.writeString(jar, "stale");
        AgentJar.stage(Main.class, artifacts.resolve("stagewright"));
        assertTrue(Files.size(jar) > 1000);
    }
}
