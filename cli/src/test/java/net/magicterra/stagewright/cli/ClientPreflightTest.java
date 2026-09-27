package net.magicterra.stagewright.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What stops a client before it starts, and what only warns. */
class ClientPreflightTest {

    @Test
    void noDisplayOnLinuxIsEnv() {
        assertThrows(EnvFailure.class, () -> ClientPreflight.requireDisplay("Linux", Map.of()));
        ClientPreflight.requireDisplay("Linux", Map.of("WAYLAND_DISPLAY", "wayland-0"));
        ClientPreflight.requireDisplay("Windows 11", Map.of());
    }

    @Test
    void anXDisplayWithNoAuthorityAnywhereIsWarnedAbout(@TempDir Path home) throws IOException {
        assertNotNull(ClientPreflight.xauthorityWarning("Linux", Map.of("DISPLAY", ":0"), home));
        assertNull(ClientPreflight.xauthorityWarning("Linux",
                Map.of("DISPLAY", ":0", "XAUTHORITY", "/run/user/1000/xauth_x"), home));
        assertNull(ClientPreflight.xauthorityWarning("Linux", Map.of("WAYLAND_DISPLAY", "wayland-0"), home));

        Files.writeString(home.resolve(".Xauthority"), "cookie");
        assertNull(ClientPreflight.xauthorityWarning("Linux", Map.of("DISPLAY", ":0"), home));
    }
}
