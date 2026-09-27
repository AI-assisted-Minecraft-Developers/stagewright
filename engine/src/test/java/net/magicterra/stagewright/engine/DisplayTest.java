package net.magicterra.stagewright.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** When a client is refused for want of a display, and when it is let through. */
class DisplayTest {

    private static final java.util.function.IntPredicate ONLY_ZERO = n -> n == 0;

    @Test
    void noDisplayAtAllIsRefusedOnLinuxOnly() {
        assertNotNull(Display.missing("Linux", Map.of(), ONLY_ZERO));
        assertNull(Display.missing("Windows 11", Map.of(), ONLY_ZERO));
    }

    @Test
    void aLocalDisplayNobodyListensOnIsRefused() {
        String why = Display.missing("Linux", Map.of("DISPLAY", ":99"), ONLY_ZERO);
        assertNotNull(why);
        assertTrue(why.contains("/tmp/.X11-unix/X99"), why);
        assertNull(Display.missing("Linux", Map.of("DISPLAY", ":0"), ONLY_ZERO));
        assertNull(Display.missing("Linux", Map.of("DISPLAY", ":0.0"), ONLY_ZERO));
        assertNull(Display.missing("Linux", Map.of("DISPLAY", "unix:0"), ONLY_ZERO));
    }

    @Test
    void remoteAndWaylandDisplaysAreNotProbed() {
        assertNull(Display.missing("Linux", Map.of("DISPLAY", "localhost:10.0"), ONLY_ZERO));
        assertNull(Display.missing("Linux", Map.of("DISPLAY", ":99", "WAYLAND_DISPLAY", "wayland-0"), ONLY_ZERO));
    }
}
