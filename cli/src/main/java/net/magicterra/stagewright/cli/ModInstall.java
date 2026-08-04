package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Puts the mods a scene run needs into the pack being tested.
 *
 * <p>Without this the CLI is a launcher for a game that has never heard of it. The author downloads
 * one jar, points it at their pack, and gets a clean boot, no results file, and a verdict that can
 * only guess why — the most likely first-run experience and the least informative one.
 *
 * <p>Installing rather than checking, because a check has the same ending: the author still has to
 * find the right jar for their loader and their Minecraft version, and getting that pairing wrong
 * fails exactly like not having it. The CLI already knows the loader — it has to, to launch at all.
 *
 * <p>StageWright's own jar rides inside this one (about 100 KB per loader, cheaper than the
 * paragraph of instructions it replaces). Anything else — a driver mod whose verbs the scenes call,
 * a pack's own mod under test — comes in through {@code --mod}, because a framework that shipped a
 * copy of its consumers would have the dependency backwards.
 *
 * <p>Every install is recorded in {@link #LEDGER} and the recorded files are deleted before the next
 * one. Overwriting by name is not enough: the jars carry versions, so an upgrade lands beside its
 * predecessor rather than on top of it, and the loader then sees two StageWrights and refuses to
 * start — or worse, picks the stale one. That exact staleness already bit this project once, when a
 * pack directory kept a hand-copied jar from before a scene API existed and the run failed reporting
 * a missing function rather than an old mod.
 */
final class ModInstall {

    /** Where the build stages the loader jars inside this fat jar. */
    private static final String RESOURCE_DIR = "/mods/";

    private static final String FRAMEWORK_PREFIX = "mc_stagewright-";

    /** Our record of what we put in mods/, so the next run can take it back out again. */
    private static final String LEDGER = ".stagewright-installed";

    private ModInstall() {}

    /**
     * Install the framework and any extra jars, replacing everything a previous run installed.
     *
     * @param loader the pack's loader, or null when detection could not tell
     * @param extras author-supplied jars to install alongside the framework
     * @return the files now sitting in mods/
     */
    static List<Path> install(Path gameDir, String loader, List<Path> extras, Consumer<String> log) {
        Path mods = gameDir.resolve("mods");
        try {
            Files.createDirectories(mods);
            sweepPreviouslyInstalled(mods, log);

            List<Path> installed = new ArrayList<>();
            installed.add(framework(mods, loader));
            for (Path extra : extras) {
                if (!Files.isRegularFile(extra)) {
                    throw new IllegalArgumentException("--mod " + extra + " is not a file");
                }
                Path target = mods.resolve(extra.getFileName().toString());
                Files.copy(extra, target, StandardCopyOption.REPLACE_EXISTING);
                installed.add(target);
            }

            writeLedger(mods, installed);
            for (Path p : installed) log.accept("installed " + p.getFileName());
            return installed;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot install mods into " + mods, e);
        }
    }

    private static Path framework(Path mods, String loader) throws IOException {
        if (loader == null) {
            throw new IllegalArgumentException("cannot tell which loader this pack runs on, so there"
                    + " is no way to pick the right StageWright build — pass --loader"
                    + " neoforge|fabric to say so, --mod <jar> to name the build yourself, or"
                    + " --no-install if the pack already has it");
        }
        String resource = RESOURCE_DIR + FRAMEWORK_PREFIX + loader + ".jar";
        Path target = mods.resolve(FRAMEWORK_PREFIX + loader + ".jar");
        try (InputStream in = ModInstall.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("this CLI carries no StageWright build for '" + loader
                        + "' (looked for " + resource + ") — pass --mod <jar> to supply one, or"
                        + " --no-install if the pack already has it");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * Delete what the last run installed, plus any StageWright build however it got there.
     *
     * <p>Two rules, because two different things are being claimed. The ledger covers jars the
     * author named with {@code --mod}: those have arbitrary names, we only know they are ours
     * because we wrote them down, and sweeping by pattern would eventually delete a mod that merely
     * looked like one of them.
     *
     * <p>{@code mc_stagewright-*.jar} is swept regardless. That is our own artifact id, so nothing
     * else can legitimately be called it — and the copy most likely to be there is one the author
     * placed by hand before this CLI could do it for them, which no ledger will ever name. Leaving
     * it produces two StageWrights in one mods folder, which is not a stale-jar problem but a
     * refuses-to-start one.
     */
    private static void sweepPreviouslyInstalled(Path mods, Consumer<String> log) throws IOException {
        Set<Path> doomed = new LinkedHashSet<>();

        Path ledger = mods.resolve(LEDGER);
        if (Files.isRegularFile(ledger)) {
            for (String name : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) continue;
                Path old = mods.resolve(trimmed);
                // A name carrying a separator would escape mods/; refuse rather than delete.
                if (!old.getParent().equals(mods)) continue;
                doomed.add(old);
            }
        }
        try (var files = Files.list(mods)) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(FRAMEWORK_PREFIX) && n.endsWith(".jar");
            }).forEach(doomed::add);
        }

        int removed = 0;
        for (Path old : doomed) {
            if (Files.deleteIfExists(old)) removed++;
        }
        if (removed > 0) log.accept("removed " + removed + " previously-installed jar(s)");
    }

    private static void writeLedger(Path mods, List<Path> installed) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (Path p : installed) names.add(p.getFileName().toString());
        Files.writeString(mods.resolve(LEDGER), String.join("\n", names) + "\n",
                StandardCharsets.UTF_8);
    }

    /**
     * Whether a StageWright jar is sitting in the pack's mods folder.
     *
     * <p>Read after a failed run to turn "one of these things went wrong" into a statement about
     * which. Only interesting when installation was skipped — when we installed it ourselves, the
     * jar being there proves nothing about whether it loaded.
     */
    static boolean frameworkPresent(Path gameDir) {
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
