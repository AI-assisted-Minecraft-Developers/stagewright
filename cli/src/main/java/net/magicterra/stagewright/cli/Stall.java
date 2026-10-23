package net.magicterra.stagewright.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What to say about a game that stopped making progress, once its agent has said it did.
 *
 * <p>Two ways of being stuck have been measured that look the same from outside and need different
 * fixes. Under Xwayland, LWJGL's own GLFW spins inside {@code glfwCreateWindow} with a core at 100%
 * and a six-line log. A failed hand-off from NeoForge's early window leaves an AWT dialog waiting for
 * a click, the process asleep. {@link #describe} says which one it is.
 */
final class Stall {

    static final int DEFAULT_MINUTES = 5;

    /**
     * What the stuck process is doing, from {@code /proc}: spinning or asleep. Two samples a few
     * seconds apart, because a single CPU-time total says nothing about now.
     */
    static String describe(ProcessHandle process) {
        Path stat = Path.of("/proc", String.valueOf(process.pid()), "stat");
        if (!Files.isRegularFile(stat)) return "its CPU use is not readable on this platform";
        try {
            long[] first = cpu(stat);
            Thread.sleep(3000);
            long[] second = cpu(stat);
            // Clock ticks are 100 per second on every Linux this runs on; 3 s is 300 of one core.
            double percent = (second[0] - first[0]) / 3.0;
            char state = (char) second[1];
            if (percent >= 50) {
                return String.format("it is SPINNING — %.0f%% of a core, state %c. Busy and silent is a"
                        + " loop that never logs, such as GLFW waiting on a window under Xwayland", percent, state);
            }
            return String.format("it is ASLEEP — %.0f%% of a core, state %c. Idle and silent is a wait"
                    + " on something that is not coming: a deadlock, or a dialog nobody will click", percent, state);
        } catch (IOException e) {
            return "its CPU use could not be read (" + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "its CPU use was not sampled";
        }
    }

    /**
     * The screen a client stalled on before ever reaching its title screen, from the director's own
     * report in its output; null when it got past that or never said.
     *
     * <p>Asked before {@link #describe}, whose reading is wrong here: a client sitting on a screen
     * nobody clicks past — a mod's update prompt — keeps rendering, so it looks busy, and "spinning"
     * would send the reader to GLFW.
     */
    static String screen(Path clientLog) {
        String screen = null;
        try {
            for (String line : new String(Files.readAllBytes(clientLog), StandardCharsets.UTF_8).split("\n")) {
                int at = line.indexOf(WAITING_ON);
                if (at >= 0) screen = line.substring(at + WAITING_ON.length()).strip();
                for (String past : PAST_THE_TITLE) {
                    if (line.contains(past)) screen = null;
                }
            }
        } catch (IOException e) {
            return null;
        }
        // No screen at all is a hang, not a prompt: describe's CPU sample is the useful answer.
        return "no screen".equals(screen) ? null : screen;
    }

    /** The ClientDirector lines {@link #screen} reads. Leaving the title screen is recognised by what
     *  the director does next, since the title screen's class name differs between loaders. */
    private static final String WAITING_ON = "waiting for the title screen, currently on ";
    private static final List<String> PAST_THE_TITLE = List.of("[mc_testkit] connecting to ",
            "[mc_testkit] opening existing world ", "[mc_testkit] creating world ",
            "[mc_testkit] client is in world after");

    /** utime+stime and the state character, from a {@code /proc/<pid>/stat} line. */
    private static long[] cpu(Path stat) throws IOException {
        String line = Files.readString(stat, StandardCharsets.US_ASCII);
        // The command name is parenthesised and may itself hold spaces and parentheses.
        String[] fields = line.substring(line.lastIndexOf(')') + 2).split(" ");
        return new long[] {Long.parseLong(fields[11]) + Long.parseLong(fields[12]), fields[0].charAt(0)};
    }
}
