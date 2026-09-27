package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The first thing that went wrong in a failed NeoForge boot, which is rarely the thing that crashed.
 *
 * <p>Measured on a 260-mod pack: a mod that touches AWT fails to construct without an X authority,
 * NeoForge enters its broken-mod state and stops dispatching lifecycle events, no mod's config loads,
 * and the first tick then crashes in an unrelated mod reading a config value "before config is
 * loaded". The crash report names that unrelated mod. The cause is only in {@code logs/debug.log},
 * several thousand lines earlier.
 */
final class FirstCause {

    private static final List<String> MARKERS =
            List.of("Failed to create mod instance", "broken mod state");
    private static final int CONTEXT_LINES = 3;

    private FirstCause() {}

    /**
     * The first marker line and a few after it, or empty — and empty too when the log predates this
     * run, which in a player's own game directory it may well do.
     */
    static List<String> find(Path gameDir, long runStartedMillis) {
        Path log = gameDir.resolve("logs").resolve("debug.log");
        try {
            if (!Files.isRegularFile(log)
                    || Files.getLastModifiedTime(log).toMillis() < runStartedMillis) {
                return List.of();
            }
            List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (MARKERS.stream().noneMatch(line::contains)) continue;
                List<String> out = new ArrayList<>();
                out.add("  first cause, from " + log + ":");
                for (int j = i; j < Math.min(lines.size(), i + 1 + CONTEXT_LINES); j++) {
                    out.add("    " + lines.get(j));
                }
                return out;
            }
        } catch (IOException e) {
            // Unreadable is the same as absent: this only ever adds detail to a failure.
        }
        return List.of();
    }
}
