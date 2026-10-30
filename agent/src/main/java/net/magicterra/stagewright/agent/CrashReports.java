package net.magicterra.stagewright.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The crash reports a game writes while it runs.
 *
 * <p>Names, not timestamps, and snapshotted when the agent starts: a game directory is reused between runs and its
 * {@code crash-reports/} accumulates, so "is there a report" is always yes. "Is there one that was not here when this
 * JVM started" is the question with an answer. A report copied in carries whatever time the copy gave it, which is why
 * modification times are not asked.
 *
 * <p>Polled by the agent's own loop, once a heartbeat: a listing of a folder that holds tens of files at most, and no
 * second thread for the one thing that can already be noticed within a second.
 */
final class CrashReports {

    private final Path directory;
    private final Set<String> seen;

    private CrashReports(Path directory, Set<String> seen) {
        this.directory = directory;
        this.seen = seen;
    }

    /** Watch {@code <gameDir>/crash-reports}, which need not exist yet. */
    static CrashReports beside(Path gameDir) {
        Path directory = gameDir.resolve("crash-reports");
        return new CrashReports(directory, names(directory));
    }

    /** The reports that appeared since the last call, as paths. Each is returned once. */
    List<String> fresh() {
        List<String> found = new ArrayList<>();
        for (String name : names(directory)) {
            if (seen.add(name)) found.add(directory.resolve(name).toString());
        }
        return found;
    }

    private static Set<String> names(Path directory) {
        Set<String> out = new HashSet<>();
        if (!Files.isDirectory(directory)) return out;
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException e) {
            // Unreadable is treated as empty: this only ever adds a reason to stop waiting, and a
            // listing failure is not worth losing the agent's other reports over.
        }
        return out;
    }
}
