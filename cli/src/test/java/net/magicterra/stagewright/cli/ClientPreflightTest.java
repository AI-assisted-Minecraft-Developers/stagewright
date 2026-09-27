package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** What stops a client before it starts. The display rules themselves are the engine's DisplayTest. */
class ClientPreflightTest {

    @Test
    void noDisplayOnLinuxIsEnv() {
        assertThrows(EnvFailure.class, () -> ClientPreflight.requireDisplay("Linux", Map.of()));
        ClientPreflight.requireDisplay("Linux", Map.of("WAYLAND_DISPLAY", "wayland-0"));
        ClientPreflight.requireDisplay("Windows 11", Map.of());
    }
}
