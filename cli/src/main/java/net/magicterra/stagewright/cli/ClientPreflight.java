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
        String os = System.getProperty("os.name", "");
        requireDisplay(os, System.getenv());
        String xauth = xauthorityWarning(os, System.getenv(), Path.of(System.getProperty("user.home")));
        if (xauth != null) log.accept(xauth);
        warnIfInUse(gameDir, log);
    }

    /** A client needs a display, and this tool does not provide one; see {@link Display}. */
    static void requireDisplay(String osName, Map<String, String> env) {
        String missing = Display.missing(osName, env);
        if (missing != null) throw new EnvFailure(missing);
    }

    /**
     * An X display with no authority file to present to it. Warned rather than refused: a server
     * started without access control needs none. But one that wants it lets GLFW in and refuses AWT,
     * so a mod that touches AWT fails to construct, NeoForge stops dispatching lifecycle events, and
     * the crash lands on the first tick in an unrelated mod reading a config that never loaded.
     *
     * @return the warning, or null
     */
    static String xauthorityWarning(String osName, Map<String, String> env, Path home) {
        if (!osName.toLowerCase(java.util.Locale.ROOT).contains("linux")) return null;
        String display = env.get("DISPLAY");
        if (display == null || display.isBlank()) return null;
        String xauthority = env.get("XAUTHORITY");
        if (xauthority != null && !xauthority.isBlank()) return null;
        if (Files.isRegularFile(home.resolve(".Xauthority"))) return null;
        return "WARNING: DISPLAY=" + display + " but XAUTHORITY is unset and there is no "
                + home.resolve(".Xauthority") + ". If that X server wants authorization, the game's"
                + " window opens but AWT is refused, and a mod using AWT fails to construct. Export"
                + " XAUTHORITY from the desktop session (under Xwayland: ls /run/user/$UID/xauth_*).";
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
