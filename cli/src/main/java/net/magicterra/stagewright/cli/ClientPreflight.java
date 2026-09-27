package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

import net.magicterra.stagewright.engine.Display;

/** What is checked before a client is started, so that a doomed launch fails in a second. */
final class ClientPreflight {

    private ClientPreflight() {}

    /** Everything checked before a client starts in {@code gameDir}. */
    static void check(Path gameDir, Consumer<String> log) {
        requireDisplay(System.getProperty("os.name", ""), System.getenv());
        warnIfInUse(gameDir, log);
    }

    /**
     * A client needs a display this tool does not provide, and an X server that lets it in; see
     * {@link Display}.
     */
    static void requireDisplay(String osName, Map<String, String> env) {
        String missing = Display.missing(osName, env);
        if (missing != null) throw new EnvFailure(missing);
    }

    /**
     * Warn when a game already seems to be running in this directory — a player's own session in
     * their {@code .minecraft}. Not refused: the run gets a world of its own. But the two share
     * {@code logs/} and {@code crash-reports/}, so a crash report the other one writes can be read as
     * this run's.
     */
    static void warnIfInUse(Path gameDir, Consumer<String> log) {
        Path latest = gameDir.resolve("logs").resolve("latest.log");
        try {
            if (!Files.isRegularFile(latest)) return;
            if (System.currentTimeMillis() - Files.getLastModifiedTime(latest).toMillis() > 60_000) return;
            long before = Files.size(latest);
            Thread.sleep(2000);
            if (Files.size(latest) != before) {
                log.accept("WARNING: " + latest + " is being written to — another game seems to be"
                        + " running in " + gameDir + ". Its logs and crash reports land in the same"
                        + " place as this run's.");
            }
        } catch (IOException e) {
            // Nothing to warn about that we can see.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
