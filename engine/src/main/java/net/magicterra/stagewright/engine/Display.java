package net.magicterra.stagewright.engine;

import java.util.Locale;
import java.util.Map;

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

    private Display() {}

    /**
     * Why a client cannot open a window here, or null if it can.
     *
     * @param env the environment the client will be started with, not necessarily this process's
     */
    public static String missing(String osName, Map<String, ?> env) {
        if (!osName.toLowerCase(Locale.ROOT).contains("linux")) return null;
        if (isSet(env.get("DISPLAY")) || isSet(env.get("WAYLAND_DISPLAY"))) return null;
        return "a client needs a display, and neither DISPLAY nor WAYLAND_DISPLAY is set. Run it in a"
                + " desktop session, or give it one — on a headless machine that is an X server the"
                + " environment starts, such as Xvfb in the CI image or xvfb-run around the command.";
    }

    private static boolean isSet(Object value) {
        return value != null && !value.toString().isBlank();
    }
}
