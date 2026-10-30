package net.magicterra.stagewright.engine.supervise;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The supervision agent's jar, which rides inside the jar of whatever launches the game and is written out where a game
 * can load it from with {@code -javaagent}.
 *
 * <p>Written beside the run's other artifacts, not into the pack's {@code mods/}: it is not a mod, and a loader that
 * found it there would try to load it as one.
 */
public final class AgentJar {

    private static final String RESOURCE = "/agent/stagewright-agent.jar";

    public static final String FILE = "stagewright-agent.jar";

    private AgentJar() {}

    /**
     * Write the agent into {@code dir} and return its path. Rewritten every run: it is small.
     *
     * @param owner a class from the jar that carries the agent as a resource; each launcher bundles its own copy, and a
     *     resource is looked up through the loader of the class asking
     */
    public static Path stage(Class<?> owner, Path dir) {
        try (InputStream in = owner.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(owner.getSimpleName() + "'s jar carries no supervision agent — it"
                        + " was not built with the bundledAgent configuration");
            }
            Files.createDirectories(dir);
            Path target = dir.resolve(FILE);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the supervision agent into " + dir, e);
        }
    }
}
