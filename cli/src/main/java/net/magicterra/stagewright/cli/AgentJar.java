package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The supervision agent's jar, which rides inside this one and is written out where a game can load
 * it from with {@code -javaagent}.
 *
 * <p>Written beside the run's other artifacts, not into the pack's {@code mods/}: it is not a mod,
 * and a loader that found it there would try to load it as one.
 */
final class AgentJar {

    private static final String RESOURCE = "/agent/stagewright-agent.jar";
    static final String FILE = "stagewright-agent.jar";

    private AgentJar() {}

    /** Write the agent into {@code artifacts} and return its path. Rewritten every run: it is small. */
    static Path stage(Path artifacts) {
        try (InputStream in = AgentJar.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("this jar carries no supervision agent — it was not built by"
                        + " cli/build.gradle");
            }
            Files.createDirectories(artifacts);
            Path target = artifacts.resolve(FILE);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the supervision agent into " + artifacts, e);
        }
    }
}
