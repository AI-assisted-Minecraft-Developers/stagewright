package net.magicterra.stagewright.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Where the run was, for the case where nothing got to say how it ended.
 *
 * <p>{@link StallWatchdog} covers the stalls it can see from inside: it names the scene, writes a
 * TIMEOUT and a footer, and halts. What it cannot cover is the process dying without it — the
 * orchestrator's wall-clock kill, an OOM, a `halt` from someone else's code, a GC spiral that
 * starves this thread too. In every one of those the results file ends mid-stream, the verdict is
 * "no done footer — the harness died mid-run", and that sentence names no scene, no tick and no
 * time. The operator's next move is to open a multi-megabyte game log and search backwards for the
 * last scene mentioned, which is a thing a machine should have written down.
 *
 * <p>This is that machine. One line, rewritten in place every poll, saying which scene is running
 * and whether the world was healthy when it was written. Because the file is TRUNCATED rather than
 * appended, it costs a constant amount of disk for a suite of any length, and reading it needs no
 * scan: whatever is in it is the last thing that was true.
 *
 * <h2>Why not a record in the results file</h2>
 *
 * <p>Because that file is the run's list of outcomes, and consumers iterate it. A heartbeat per
 * second is 150 lines in a gate run and some thousands in a long suite — enough to bury the records
 * an operator opens the file to read, in service of a fact that is only ever interesting once, at
 * the end, and only when something went wrong.
 *
 * <h2>Why a failure to write is swallowed here</h2>
 *
 * <p>{@link ResultsJsonl} throws when it cannot write, and is right to: a run whose outcomes are not
 * being recorded must not be allowed to look green. This file is the opposite kind of thing. It
 * carries no verdict and nothing is judged on it, so a full disk or a locked file must not be
 * permitted to end a run that is otherwise fine. The cost of losing it is a less informative message
 * in a failure that has already happened.
 */
final class Heartbeat {

    /** Beside the results file, and deleted with it at provision — a heartbeat left over from the
     *  previous run would answer "where did it die" with a different run's scene, which is worse
     *  than not answering. */
    static final String FILE = "stagewright-progress.json";

    private final Path file;

    Heartbeat(Path file) {
        this.file = file;
    }

    /**
     * @param ticksInWindow ticks delivered so far in the watchdog's open window, and {@code
     *                      windowMs} the wall time they took. Written as the two raw numbers rather
     *                      than a rate: the reader wants to know whether the world was healthy when
     *                      the process died, and "3 ticks in 41 s" says that in a way a rounded
     *                      0.07 does not.
     */
    void write(String scene, long ticks, long ticksInWindow, long windowMs) {
        String line = "{\"type\":\"progress\",\"scene\":\"" + ResultsJsonl.escape(scene == null ? "" : scene)
                + "\",\"ticks\":" + ticks
                + ",\"ticksInWindow\":" + ticksInWindow
                + ",\"windowMs\":" + windowMs
                + ",\"atMs\":" + System.currentTimeMillis()
                + "}\n";
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            // Deliberately silent — see the class note. Logging per poll would also turn a disk
            // problem into a second flood in the log the operator is already trying to read.
        }
    }
}
