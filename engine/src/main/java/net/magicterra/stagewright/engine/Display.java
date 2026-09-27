package net.magicterra.stagewright.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a game client can open a window — asked by every supervisor before it starts one.
 *
 * <p>Neither supervisor provides a display. That is the machine's, the user's or the CI image's to
 * supply, and a test runner that starts X servers ends up owning their leaks, their probing and their
 * platform quirks. What a supervisor can do is refuse early: without a display GLFW fails to
 * initialise within two seconds and leaves a fourteen-line log ending in "glfwInit failed", which
 * reads like an early crash of the game rather than a missing piece of the environment.
 */
public final class Display {

    /** A display on this machine: {@code :0}, {@code :1.0}, {@code unix:0}. {@code host:0} is remote. */
    private static final Pattern LOCAL = Pattern.compile("^(?:unix)?:(\\d+)(?:\\.\\d+)?$");

    private Display() {}

    /**
     * Why a client cannot open a window here, or null if it can.
     *
     * @param env the environment the client will be started with, not necessarily this process's
     */
    public static String missing(String osName, Map<String, ?> env) {
        return missing(osName, env, Display::xServerListening);
    }

    static String missing(String osName, Map<String, ?> env, IntPredicate xServerListening) {
        if (!osName.toLowerCase(Locale.ROOT).contains("linux")) return null;
        if (isSet(env.get("WAYLAND_DISPLAY"))) return null;
        if (!isSet(env.get("DISPLAY"))) {
            return "a client needs a display, and neither DISPLAY nor WAYLAND_DISPLAY is set. Run it in"
                    + " a desktop session, or give it one — on a headless machine that is an X server the"
                    + " environment starts, such as Xvfb in the CI image or xvfb-run around the command.";
        }
        String display = env.get("DISPLAY").toString().trim();
        Matcher local = LOCAL.matcher(display);
        if (local.matches() && !xServerListening.test(Integer.parseInt(local.group(1)))) {
            return "DISPLAY=" + display + ", but no X server is listening on it (no /tmp/.X11-unix/X"
                    + local.group(1) + " socket). Point DISPLAY at the X server that is running, or"
                    + " start one first.";
        }
        return null;
    }

    /**
     * Whether an X server listens on local display {@code n}. Read from {@code /proc/net/unix}, which
     * also lists the abstract socket an X server may hold without the file under /tmp; the file is
     * the fallback where that table cannot be read.
     */
    private static boolean xServerListening(int n) {
        String path = "/tmp/.X11-unix/X" + n;
        try {
            List<String> sockets = Files.readAllLines(Path.of("/proc/net/unix"));
            for (String line : sockets) {
                if (line.endsWith(" " + path) || line.endsWith(" @" + path)) return true;
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return Files.exists(Path.of(path));
        }
    }

    private static boolean isSet(Object value) {
        return value != null && !value.toString().isBlank();
    }
}
