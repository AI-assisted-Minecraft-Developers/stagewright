package net.magicterra.stagewright.cli.install;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Where a client install lives when nobody says otherwise: the official launcher's {@code .minecraft}.
 *
 * <p>The same directory HMCL defaults its common directory to, by the same per-OS rule, so a box that
 * already has Minecraft installed by either launcher downloads nothing that is already there — the
 * assets alone are over 800 MB.
 */
public final class MinecraftDir {

    private MinecraftDir() {}

    public static Path defaultDir() {
        return defaultFor(System.getProperty("os.name", ""), System.getenv(),
                System.getProperty("user.home", "."));
    }

    static Path defaultFor(String osName, Map<String, String> env, String home) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = env.get("APPDATA");
            return Path.of(appData == null || appData.isBlank() ? home : appData, ".minecraft");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(home, "Library", "Application Support", "minecraft");
        }
        return Path.of(home, ".minecraft");
    }
}
