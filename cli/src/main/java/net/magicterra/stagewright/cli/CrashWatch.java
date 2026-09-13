package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Notices that the game wrote a crash report, so a run that is over stops being waited on.
 *
 * <p><b>A crashed Minecraft frequently does not exit.</b> That is the whole reason this exists: the
 * obvious signal for "the run ended badly" is the process dying, and on a modpack it does not fire.
 * One mod's thread pool with no shutdown — a few dozen {@code AwsEventLoop} threads was the measured
 * case — keeps the JVM in the process table long after the crash report is on disk and the window is
 * gone. Waiting on {@code Process.isAlive} there burns the entire {@code --timeout} and then reports
 * "the run wrote no results", which is true, forty-five minutes late, and says nothing about the
 * crash that has been sitting in {@code crash-reports/} since minute three.
 *
 * <p>Names, not timestamps, and snapshotted before the launch. A pack directory is reused between
 * runs and its {@code crash-reports/} accumulates, so "is there a crash report" is always yes and
 * would fail every run before it started. "Is there one that was not here when I started" is the
 * question with an answer. Modification times are not used to decide NEW because a report copied or
 * restored into the directory carries whatever time the copy gave it; they are only used to pick the
 * most recent of several new ones, where being wrong costs a wrong filename in a message rather than
 * a wrong verdict.
 */
final class CrashWatch {

    private final Path directory;
    private final Set<String> before;

    private CrashWatch(Path directory, Set<String> before) {
        this.directory = directory;
        this.before = before;
    }

    /** Start watching {@code <gameDir>/crash-reports}, which need not exist yet. */
    static CrashWatch on(Path gameDir) {
        Path directory = gameDir.resolve("crash-reports");
        return new CrashWatch(directory, names(directory));
    }

    /**
     * The report this run wrote, or null.
     *
     * <p>Cheap enough to call once a second: one directory listing of a folder that holds tens of
     * files at most.
     */
    Path fresh() {
        Set<String> now = names(directory);
        now.removeAll(before);
        if (now.isEmpty()) return null;
        return now.stream()
                .map(directory::resolve)
                .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                .orElse(null);
    }

    /** One clause naming what was found, for a sentence that has already said something crashed. */
    String describe() {
        Path report = fresh();
        return report != null
                ? "the report is at " + report
                : "no crash report was written, so look at the game log";
    }

    private static Set<String> names(Path directory) {
        if (!Files.isDirectory(directory)) return new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            Set<String> out = new LinkedHashSet<>();
            files.filter(Files::isRegularFile).forEach(p -> out.add(p.getFileName().toString()));
            return out;
        } catch (IOException e) {
            // Unreadable is treated as empty rather than fatal: this watcher only ever adds a
            // reason to stop waiting, and refusing to run a suite over a listing failure would be a
            // worse trade than losing the reason.
            return new LinkedHashSet<>();
        }
    }
}
