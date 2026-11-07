package net.magicterra.stagewright.testengine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Processes still running in, or naming, a scene's game directories once its games have ended.
 *
 * <p>Ending a game ends what it started while it ran, but not what it starts as it goes down: a pack's crash watcher
 * launches its own JVM from the game's shutdown, and that one is no longer below the game when the game is gone. Such a
 * process is told apart by where it runs, which on Linux {@code /proc} says, or by its command line naming the
 * directory, where the system shows command lines; a scene's directory is its own, so nothing another run started is
 * taken for one.
 */
final class Leftovers {

    private static final Path PROC = Path.of("/proc");

    /** Whether the system shows each process's directory there, as Linux does. */
    private static final boolean HAS_PROC = Files.isDirectory(PROC);

    /**
     * How long a process found gets to finish exiting: one just killed with its game is still doing so, and a pack's
     * crash watcher that saw the game stop cleanly checks a licence over the network before it goes, which took over 3
     * seconds. Waited only while one is alive; {@link #SETTLE_PARAMETER} sets another.
     */
    static final long DEFAULT_SETTLE_SECONDS = 15;

    /** The configuration parameter that sets how many seconds a process found gets to exit. */
    static final String SETTLE_PARAMETER = "stagewright.leftovers.settleSeconds";

    private static final long SETTLE_POLL_MILLIS = 100;

    /** A command line is cut here: a game's classpath alone runs to tens of kilobytes. */
    private static final int COMMAND_CHARS = 300;

    private Leftovers() {}

    /**
     * One line per process found still running {@code settle} after the games ended, {@code pid N: command}, in pid
     * order; empty when there is none. The run's own games are left out: one still alive here is being ended, which is
     * not something it left.
     */
    static List<String> in(List<Path> directories, Set<Long> games, Instant since, Duration settle)
            throws InterruptedException {
        List<String> roots = roots(directories);
        long self = ProcessHandle.current().pid();
        Map<ProcessHandle, String> found = new TreeMap<>(Comparator.comparingLong(ProcessHandle::pid));
        ProcessHandle.allProcesses()
                .filter(p -> p.pid() != self && !games.contains(p.pid()))
                .forEach(p -> {
                    ProcessHandle.Info info = p.info();
                    if (!startedBefore(info, since) && ours(p, info, roots)) {
                        found.put(p, info.commandLine().or(info::command).orElse("?"));
                    }
                });
        // Polled: none of these is this JVM's child, and waiting on one's onExit would start a thread to poll it
        // anyway.
        long deadline = System.nanoTime() + settle.toNanos();
        while (found.keySet().stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() - deadline < 0) {
            Thread.sleep(SETTLE_POLL_MILLIS);
        }
        List<String> left = new ArrayList<>();
        found.forEach((p, command) -> {
            if (p.isAlive()) {
                left.add("pid " + p.pid() + ": "
                        + (command.length() > COMMAND_CHARS ? command.substring(0, COMMAND_CHARS) + "…" : command));
            }
        });
        return left;
    }

    /** Each directory as given and, where it exists, as the system resolves it, which is what {@code /proc} shows. */
    private static List<String> roots(List<Path> directories) {
        Set<String> roots = new LinkedHashSet<>();
        for (Path d : directories) {
            roots.add(d.toAbsolutePath().normalize().toString());
            try {
                roots.add(d.toRealPath().toString());
            } catch (IOException e) {
                // Gone already; the path as given is all there is to match.
            }
        }
        return List.copyOf(roots);
    }

    /**
     * Whether {@code p} was already running when {@code since} came: a run's directories are made again each time, so
     * what an earlier run or round left there names them too. A process whose start the system does not give counts.
     */
    private static boolean startedBefore(ProcessHandle.Info info, Instant since) {
        return info.startInstant().map(start -> start.isBefore(since)).orElse(false);
    }

    private static boolean ours(ProcessHandle p, ProcessHandle.Info info, List<String> roots) {
        if (!p.isAlive()) {
            return false;
        }
        String command = info.commandLine().orElse("");
        return roots.stream().anyMatch(root -> names(command, root))
                || cwd(p.pid()).map(cwd -> under(cwd, roots)).orElse(false);
    }

    /**
     * Whether {@code path} is one of {@code roots} or below one; a removed directory reads with " (deleted)" after it.
     */
    static boolean under(String path, List<String> roots) {
        String bare = path.endsWith(" (deleted)") ? path.substring(0, path.length() - " (deleted)".length()) : path;
        return roots.stream().anyMatch(root -> bare.equals(root) || bare.startsWith(root + "/"));
    }

    /**
     * Whether {@code command} names {@code root} as a whole path: nothing of a path comes before it, and what follows
     * it ends the path or goes below it, so {@code runs/smoke} is not found in {@code runs/smokeFast} and
     * {@code /home/u} not in {@code /backup/home/u}.
     */
    static boolean names(String command, String root) {
        for (int at = command.indexOf(root); at >= 0; at = command.indexOf(root, at + 1)) {
            int next = at + root.length();
            boolean starts = at == 0 || !continuesThePath(command.charAt(at - 1));
            boolean ends = next == command.length() || !continuesTheName(command.charAt(next));
            if (starts && ends) {
                return true;
            }
        }
        return false;
    }

    private static boolean continuesThePath(char c) {
        return continuesTheName(c) || c == '/' || c == '\\';
    }

    private static boolean continuesTheName(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-';
    }

    /** Where {@code pid} runs, where the system says so and lets this user read it. */
    private static Optional<String> cwd(long pid) {
        if (!HAS_PROC) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    Files.readSymbolicLink(PROC.resolve(Long.toString(pid)).resolve("cwd"))
                            .toString());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return Optional.empty();
        }
    }
}
