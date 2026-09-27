package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** What is checked before a client is started, so that a doomed launch fails in a second. */
final class ClientPreflight {

    private ClientPreflight() {}

    /**
     * A client needs a display, and this tool does not provide one — that is the machine's, the
     * user's or the CI image's to supply. Without one GLFW fails to initialise within two seconds and
     * leaves a fourteen-line log ending in "glfwInit failed", which reads like an early crash.
     */
    static void requireDisplay(String osName, Map<String, String> env) {
        if (!osName.toLowerCase(Locale.ROOT).contains("linux")) return;
        if (isSet(env.get("DISPLAY")) || isSet(env.get("WAYLAND_DISPLAY"))) return;
        throw new EnvFailure("a client needs a display, and neither DISPLAY nor WAYLAND_DISPLAY is set."
                + " Run it in a desktop session, or give it one — on a headless machine that is an X"
                + " server the environment starts, such as Xvfb in the CI image.");
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

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
