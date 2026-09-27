package net.magicterra.stagewright.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What earlier versions of StageWright copied into a run directory's {@code mods/} folder.
 *
 * <p>Neither supervisor copies anything there. The CLI hands its jars to the loader
 * ({@code -Dfabric.addMods}, {@code --fml.mods}), because its game directory may be a player's own
 * {@code .minecraft}; the Gradle plugin leaves the harness to the build's own run dependencies
 * ({@code modLocalRuntime} under loom, {@code localRuntime} under ModDevGradle). What is left is
 * taking back the copies older versions made — recorded in {@link #LEDGER} — since they would load
 * beside the harness handed over now, as a duplicate mod.
 */
public final class ModInstall {

    /** Our own artifact id prefix, for recognising a StageWright build in a pack's mods/. */
    public static final String FRAMEWORK_PREFIX = "mc_stagewright-";

    /** Where earlier versions recorded what they put in mods/. */
    private static final String LEDGER = ".stagewright-installed";

    private ModInstall() {}

    /**
     * Take back what an earlier version put in mods/.
     *
     * <p>Only what the ledger names. A StageWright jar the ledger does not name is left alone: this
     * runs against a player's own {@code .minecraft}, and a jar no run of ours put there is the
     * pack's or the player's.
     */
    public static void uninstall(Path gameDir, Consumer<String> log) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return;
        try {
            int removed = 0;
            for (Path old : previouslyInstalled(mods)) {
                if (Files.deleteIfExists(old)) removed++;
            }
            if (removed > 0) log.accept("removed " + removed + " jar(s) an earlier run put in " + mods);
            Files.deleteIfExists(mods.resolve(LEDGER));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clean StageWright's jars out of " + mods, e);
        }
    }

    /** The files the ledger says an earlier install put in mods/. */
    private static Set<Path> previouslyInstalled(Path mods) throws IOException {
        Set<Path> ours = new LinkedHashSet<>();
        Path ledger = mods.resolve(LEDGER);
        if (!Files.isRegularFile(ledger)) return ours;
        for (String name : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            Path old = mods.resolve(trimmed);
            // A name carrying a separator would escape mods/; refuse rather than delete.
            if (!old.getParent().equals(mods)) continue;
            ours.add(old);
        }
        return ours;
    }

    /**
     * Whether a StageWright jar is sitting in this run directory's mods folder — the pack ships it.
     *
     * <p>Asked before handing the CLI's own build over, which would be a duplicate mod, and after a
     * failed run, to turn "one of these things went wrong" into a statement about which.
     */
    public static boolean frameworkPresent(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return false;
        try (var files = Files.list(mods)) {
            return files.anyMatch(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(FRAMEWORK_PREFIX) && n.endsWith(".jar");
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + mods, e);
        }
    }
}
