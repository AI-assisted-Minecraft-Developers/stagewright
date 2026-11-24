package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps NeoForge's early loading window off in a scene's client.
 *
 * <p>The window is FML's own GL context, painting a progress screen while the game loads, which nobody watches in a
 * scene. Its renderer has crashed a client in the loading overlay before it reached the world (a
 * {@code FileSystemNotFoundException} linking a lambda in {@code RenderElement.memoryInfo}), failing every test of the
 * scene. FML reads the switch only from {@code config/fml.toml} in the game directory, with no system property, and
 * that directory is the scene's own copy, so the copy's file is where it is turned off.
 */
final class EarlyWindow {

    /** Where FML reads its settings, relative to the game directory. */
    static final String CONFIG = "config/fml.toml";

    private static final String KEY = "earlyWindowControl";

    private static final String OFF = KEY + " = false";

    private static final Pattern SETTING = Pattern.compile("(?m)^[ \\t]*" + KEY + "[ \\t]*=.*$");

    private EarlyWindow() {}

    /**
     * Whether {@code process} starts a NeoForge client: FML opens its window for the {@code forgeclient} targets only.
     */
    static boolean neoForgeClient(LaunchSpec.Process process) throws IOException {
        List<String> words = new ArrayList<>();
        for (String a : process.args()) {
            Path file = a.startsWith("@") && a.length() > 1 ? Path.of(a.substring(1)) : null;
            if (file != null && Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank() && !line.strip().startsWith("#")) {
                        words.addAll(List.of(line.strip().split("\\s+")));
                    }
                }
            } else {
                words.add(a);
            }
        }
        for (int i = 0; i + 1 < words.size(); i++) {
            if ("--launchTarget".equals(words.get(i)) && words.get(i + 1).startsWith("forgeclient")) {
                return true;
            }
        }
        return false;
    }

    /** Set the window off in {@code gameDir}'s FML settings, keeping every other line, or write them with only that. */
    static void turnOff(Path gameDir) throws IOException {
        Path file = gameDir.resolve(CONFIG);
        SceneDirectory.writable(gameDir, file);
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, OFF + "\n", StandardCharsets.UTF_8);
            return;
        }
        String body = Files.readString(file, StandardCharsets.UTF_8);
        Matcher setting = SETTING.matcher(body);
        // A key added at the top stays outside any table further down.
        String patched = setting.find() ? setting.replaceAll(Matcher.quoteReplacement(OFF)) : OFF + "\n" + body;
        if (!patched.equals(body)) {
            Files.writeString(file, patched, StandardCharsets.UTF_8);
        }
    }
}
