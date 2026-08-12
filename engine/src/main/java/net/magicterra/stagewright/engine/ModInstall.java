package net.magicterra.stagewright.engine;

import java.io.IOException;
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
 * Puts the jars a scene run needs into a run directory's {@code mods/} folder.
 *
 * <p>Shared by the CLI and the Gradle plugin for the same reason {@link RunDirectory} is: the rules
 * are not obvious, getting one wrong produces a run that looks fine and proves nothing, and two
 * copies would drift.
 *
 * <h2>Why mods/ and not the classpath</h2>
 *
 * <p>Because the classpath does not work, and fails in the worst possible way. Adding the harness
 * jar to a dev run's runtime classpath under ModDevGradle does get it in front of FML — the
 * discoverer even logs the jar by name — but it is claimed as a plain game library and never appears
 * in the mod list. The run then boots cleanly, ticks, writes no results, and the only report is "the
 * game never armed" over a log containing no error and, in the case that cost this project an
 * afternoon, not one occurrence of the word stagewright.
 *
 * <p>{@code mods/} is what FML's {@code ModsFolderLocator} reads in every run, dev or production, on
 * both loaders. It also means the run loads the exact artifact a pack author would install rather
 * than a dev-only variant of it, which is the difference between testing what ships and testing what
 * builds.
 *
 * <p>Under architectury-loom this is unnecessary — {@code modLocalRuntime} already does it — which is
 * exactly why it stayed missing: worlddriver, the only consumer for a long time, is a loom build.
 * The first ModDevGradle consumer found the hole immediately.
 *
 * <h2>The ledger</h2>
 *
 * <p>Every install is recorded in {@link #LEDGER} and the recorded files are deleted before the next
 * one. Overwriting by name is not enough: the jars carry versions, so an upgrade lands BESIDE its
 * predecessor rather than on top of it, and the loader then sees two StageWrights — which either
 * refuses to start or, worse, arms the stale one and reports its behaviour as the new code's.
 */
public final class ModInstall {

    /** Our own artifact id prefix. Nothing else can legitimately be called this, so it is swept
     *  regardless of what the ledger says — the copy most likely to be there is one somebody placed
     *  by hand, which no ledger will ever name. */
    public static final String FRAMEWORK_PREFIX = "mc_stagewright-";

    /** Our record of what we put in mods/, so the next run can take it back out again. */
    private static final String LEDGER = ".stagewright-installed";

    private ModInstall() {}

    /**
     * Install these jars into {@code gameDir/mods}, replacing everything a previous install left.
     *
     * @param jars absolute paths to the jars to install, in the order they should be reported
     * @return the files now sitting in mods/
     */
    public static List<Path> install(Path gameDir, List<Path> jars, Consumer<String> log) {
        Path mods = gameDir.resolve("mods");
        try {
            Files.createDirectories(mods);
            sweep(mods, log);

            List<Path> installed = new ArrayList<>();
            for (Path jar : jars) {
                if (!Files.isRegularFile(jar)) {
                    throw new IllegalArgumentException(jar + " is not a file, so it cannot be"
                            + " installed into " + mods);
                }
                Path target = mods.resolve(jar.getFileName().toString());
                Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
                installed.add(target);
            }

            writeLedger(mods, installed);
            for (Path p : installed) log.accept("installed " + p.getFileName());
            return installed;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot install mods into " + mods, e);
        }
    }

    /**
     * Delete what the last install left, plus any StageWright build however it got there.
     *
     * <p>Two rules, because two different things are being claimed. The ledger covers jars the
     * caller named: those have arbitrary names, we only know they are ours because we wrote them
     * down, and sweeping by pattern would eventually delete a mod that merely looked like one of
     * them. {@link #FRAMEWORK_PREFIX} is swept unconditionally, per its own reasoning above.
     */
    private static void sweep(Path mods, Consumer<String> log) throws IOException {
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
            files.filter(ModInstall::isFrameworkJar).forEach(doomed::add);
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

    private static boolean isFrameworkJar(Path p) {
        String n = p.getFileName().toString();
        return n.startsWith(FRAMEWORK_PREFIX) && n.endsWith(".jar");
    }

    /**
     * Whether a StageWright jar is sitting in this run directory's mods folder.
     *
     * <p>Read after a failed run to turn "one of these things went wrong" into a statement about
     * which. Only interesting when installation was skipped — when we installed it ourselves, the
     * jar being there proves nothing about whether it loaded.
     */
    public static boolean frameworkPresent(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return false;
        try (var files = Files.list(mods)) {
            return files.anyMatch(ModInstall::isFrameworkJar);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + mods, e);
        }
    }
}
