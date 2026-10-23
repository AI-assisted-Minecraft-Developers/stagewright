package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

import net.magicterra.stagewright.engine.supervise.Supervisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentJarTest {

    @Test
    void theAgentRidesInsideTheRunnerAndIsWrittenWhereAGameCanLoadItFrom(@TempDir Path artifacts) throws Exception {
        Path jar = AgentJar.stage(artifacts.resolve("stagewright"));
        assertEquals(artifacts.resolve("stagewright").resolve(AgentJar.FILE), jar);
        try (JarFile file = new JarFile(jar.toFile())) {
            assertEquals("net.magicterra.stagewright.agent.Agent",
                    file.getManifest().getMainAttributes().getValue("Premain-Class"));
        }
        // A second run replaces it rather than failing on the file left by the first.
        Files.writeString(jar, "stale");
        AgentJar.stage(artifacts.resolve("stagewright"));
        assertTrue(Files.size(jar) > 1000);
    }

    @Test
    void aLeakIsNamedWithTheJarsItsStackRunsThrough() {
        List<String> lines = Main.leakLines(List.of(new Supervisor.Leak("mod-pool-1",
                List.of("java.lang.Thread.sleep(Native Method)", "x.Pool.run(Pool.java:9)"),
                List.of("file:/mods/x.jar"))));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("WARNING:"), lines.toString());
        // The JDK's own frames, with or without their module prefix, say nothing about whose thread it is.
        assertEquals("  mod-pool-1 at x.Pool.run(Pool.java:9)  [file:/mods/x.jar]", lines.get(1));
    }

    @Test
    void noLeakSaysNothing() {
        assertEquals(List.of(), Main.leakLines(List.of()));
    }

    @Test
    void failOnLeakIsAFlagNotAnOptionWithAValue() {
        assertEquals("true", Args.parse(new String[] {"--game-dir", "g", "--fail-on-leak"}).opts().get("fail-on-leak"));
    }
}
