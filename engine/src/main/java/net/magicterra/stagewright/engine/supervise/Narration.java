package net.magicterra.stagewright.engine.supervise;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What to say about how a supervised run ended, worded once for the command-line runner and the Gradle
 * plugin. Two launchers that each worded "the game never reached its agent" would, sooner or later,
 * word it differently, and a reader comparing a CI log with a local one would take that for two causes.
 */
public final class Narration {

    private Narration() {}

    /** One line per thread that held a finished JVM up, naming the jars its stack runs through. */
    public static List<String> leaks(List<Supervisor.Leak> leaks) {
        List<String> lines = new ArrayList<>();
        if (leaks.isEmpty()) return lines;
        lines.add("WARNING: the suite finished but the game's JVM did not end; its agent ended it."
                + " Non-daemon threads still running:");
        for (Supervisor.Leak leak : leaks) {
            lines.add("  " + leak.thread() + " at " + ownFrame(leak.stack())
                    + (leak.jars().isEmpty() ? "" : "  [" + String.join(", ", leak.jars()) + "]"));
        }
        return lines;
    }

    /**
     * The first frame that is not the JDK's: a held thread is always parked in {@code Thread.sleep} or
     * a lock, and that says nothing about whose it is. Falls back to the top frame.
     */
    static String ownFrame(List<String> stack) {
        for (String frame : stack) {
            String method = frame.contains("/") ? frame.substring(frame.indexOf('/') + 1) : frame;
            if (!method.startsWith("java.") && !method.startsWith("jdk.") && !method.startsWith("sun.")) {
                return frame;
            }
        }
        return stack.isEmpty() ? "no stack" : stack.get(0);
    }

    public static String neverConnected(String half, Duration limit) {
        return "ENV — the " + half + " was running but never reached the supervision agent in "
                + limit.toSeconds() + "s. The agent is the first thing a JVM runs, so this one was not started"
                + " with -javaagent: a launch command that drops JVM arguments would do it.";
    }

    public static String jvmStopped(String half, Duration limit, String phase, long ticks) {
        return "the whole " + half + " JVM stopped: no heartbeat from its agent for " + limit.toSeconds()
                + "s, last in phase '" + phase + "' at " + ticks + " ticks. A JVM that stops its own daemon"
                + " threads is wedged in native code or at a safepoint that never ends. Killed.";
    }

    /**
     * @param screen     the screen a client is sitting on, or null
     * @param processState what the operating system says the process is doing, or null
     */
    public static String noProgress(String half, int stallMinutes, String phase, long ticks, String screen,
                                    String processState) {
        return "the " + half + " is alive and its agent heartbeats, but nothing it reports moved for "
                + stallMinutes + (stallMinutes == 1 ? " minute" : " minutes") + ": phase '" + phase + "', "
                + ticks + " ticks, no new classes loaded"
                + (screen == null ? "" : ", and it is sitting on " + screen + ", a screen nobody is going to"
                        + " click past")
                + "." + (processState == null ? "" : " " + processState + ".") + " Killed.";
    }

    /** Where the game's threads were when it stopped moving, one thread to a block. */
    public static List<String> stacks(List<Supervisor.ThreadStack> stacks) {
        List<String> lines = new ArrayList<>();
        for (Supervisor.ThreadStack stack : stacks) {
            lines.add("  " + stack.thread() + ": " + String.join("\n      at ", stack.frames()));
        }
        return lines;
    }
}
