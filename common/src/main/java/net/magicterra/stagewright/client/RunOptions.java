package net.magicterra.stagewright.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.StageWrightCommon;
import net.minecraft.client.NarratorStatus;
import net.minecraft.client.Options;

/**
 * The four client settings a scene run cannot be correct without — set in memory, never saved.
 *
 * <ul>
 *   <li>{@code pauseOnLostFocus:false} — vanilla singleplayer pauses when the window is deactivated.
 *       On a developer's desktop that happens constantly, and the pause screen does not merely stall
 *       the run: it sits underneath every later assertion, so one focus slip turns a clean run into
 *       a page of unrelated-looking failures.</li>
 *   <li>{@code onboardAccessibility:false} — a fresh game directory opens the accessibility
 *       onboarding screen before the title screen.</li>
 *   <li>{@code narrator:0} — nothing should attempt text-to-speech on a headless CI box.</li>
 *   <li>{@code enableVsync:false} — with vsync on, the client's only thread blocks in
 *       {@code glfwSwapBuffers}, and a compositor that is not presenting the window hands out frames
 *       at about 1 Hz. Minecraft runs at most ten ticks per frame, so the client falls to half the
 *       integrated server's rate and every tick-budgeted scene fails in a body-shaped way. Measured:
 *       three jstacks of a 1 fps run sat in {@code RenderSystem.flipFrame}, and the failing scenes came
 *       in at almost exactly 2x their green tick counts.</li>
 * </ul>
 *
 * <p>They are set in memory and never written into {@code options.txt}: the game directory may be a
 * player's own {@code .minecraft}, where that file holds their key bindings. In memory is not enough
 * by itself: the game saves its options whenever a tutorial step advances, the first server is
 * joined or a resource pack fails to load, and each save would write these four values with it. So
 * every save is followed by {@link #restoreFile}, which puts the file's own lines for these keys back.
 */
public final class RunOptions {

    private static final List<String> KEYS =
            List.of("pauseOnLostFocus", "onboardAccessibility", "narrator", "enableVsync");

    /** The file's own line for each key before the run; a key the file lacked is absent. Null until
     *  {@link #apply} runs, which is what keeps {@link #restoreFile} inert in an ordinary game. */
    private static volatile Map<String, String> original;

    private RunOptions() {}

    /**
     * After {@code Options.load}, when a StageWright director drives this client.
     *
     * <p>At load, not at the first tick: the narrator, the window's swap interval and the first screen
     * are all built from these values inside the {@code Minecraft} constructor, before any tick. Too
     * late, and a player's {@code narrator:2} on a machine without libflite opens a modal "Failed to
     * initialize text-to-speech library" dialog that waits for a click forever. The game is not yet
     * running here, so {@code OptionInstance.set} stores the value without firing its callbacks.
     */
    public static void apply(Options options, File file) {
        if (!ClientDirector.directed()) return;
        // The file's own lines are captured once; a second load re-applies the run's values over them.
        if (original == null) original = keyLines(file.toPath());
        options.pauseOnLostFocus = false;
        options.onboardAccessibility = false;
        options.narrator().set(NarratorStatus.OFF);
        options.enableVsync().set(false);
        StageWrightCommon.LOG.info("[{}] run options set in memory: {} (options.txt keeps its own)",
                StageWrightCommon.MOD_ID, KEYS);
    }

    /** After {@code Options.save}: the run's four values out of the file, the player's back in. */
    public static void restoreFile(File file) {
        Map<String, String> saved = original;
        if (saved == null) return;
        Path path = file.toPath();
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String key = key(line);
                if (key == null) {
                    out.add(line);
                } else if (saved.containsKey(key)) {
                    out.add(saved.get(key));
                }
                // A key the file never had is dropped, so the game's default applies next time.
            }
            Files.write(path, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            StageWrightCommon.LOG.warn("[{}] could not restore the player's settings in {}: {}",
                    StageWrightCommon.MOD_ID, path, e.toString());
        }
    }

    private static Map<String, String> keyLines(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String key = key(line);
                if (key != null) out.put(key, line);
            }
        } catch (IOException e) {
            StageWrightCommon.LOG.warn("[{}] could not read {}: {}", StageWrightCommon.MOD_ID, file,
                    e.toString());
        }
        return out;
    }

    private static String key(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return null;
        String key = line.substring(0, colon);
        return KEYS.contains(key) ? key : null;
    }
}
