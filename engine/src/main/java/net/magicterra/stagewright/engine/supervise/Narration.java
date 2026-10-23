package net.magicterra.stagewright.engine.supervise;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What to say about how a supervised run ended, worded once for the command-line runner and the Gradle
 * plugin. Two launchers that each worded "the game never reached its agent" would, sooner or later,
 * word it differently, and a reader comparing a CI log with a local one would take that for two causes.
 */
public final class Narration {

    private Narration() {}

    /**
     * One line per kind of thread that held a finished JVM up: threads that differ only in a trailing
     * number are one line with a count, since a pool of twenty-four workers is one defect.
     *
     * <p>Where the JVM recorded who started the thread, the line says that, with the jars it came from:
     * a parked worker's own stack is all JDK. Otherwise it says where the thread is, as before.
     */
    public static List<String> leaks(List<Supervisor.Leak> leaks) {
        List<String> lines = new ArrayList<>();
        if (leaks.isEmpty()) return lines;
        lines.add("WARNING: the suite finished but the game's JVM did not end; its agent ended it."
                + " Non-daemon threads still running:");
        Map<String, List<Supervisor.Leak>> kinds = new LinkedHashMap<>();
        for (Supervisor.Leak leak : leaks) {
            kinds.computeIfAbsent(kind(leak.thread()), k -> new ArrayList<>()).add(leak);
        }
        for (Map.Entry<String, List<Supervisor.Leak>> kind : kinds.entrySet()) {
            List<Supervisor.Leak> same = kind.getValue();
            Supervisor.Leak first = same.get(0);
            String name = same.size() == 1 ? first.thread() : kind.getKey() + " x" + same.size();
            boolean started = !first.origin().isEmpty();
            List<String> jars = started ? first.originJars() : first.jars();
            lines.add("  " + name + (started ? " started at " + first.origin().get(0) : " at " + ownFrame(first.stack()))
                    + (jars.isEmpty() ? "" : "  [" + String.join(", ", jars) + "]"));
        }
        return lines;
    }

    /** A thread's name without its trailing number: {@code pool-2-thread-12} is {@code pool-2-thread-*}. */
    static String kind(String name) {
        return name.replaceFirst("[-#_ ]?\\d+$", "-*");
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

    private static final Pattern WINDOW_CALL = Pattern.compile("org\\.lwjgl\\.glfw\\.GLFW\\.n?glfw(CreateWindow|ShowWindow)\\(");

    /**
     * What a stuck start is stuck on, from where the game's threads were, or null when no known way
     * of being stuck fits.
     *
     * <p>Known: the game's main or render thread inside GLFW's window creation or showing. A window
     * takes well under a second to open, and the stacks are only sampled once nothing has moved for
     * the agent's idle time, so a thread found there is not opening one. It was measured under
     * Xwayland, where LWJGL's own GLFW waits for a visibility event the compositor never sends — a
     * locked screen was the usual cause — with the thread running at a full core and the log six lines
     * long. A failed-window dialog from NeoForge is not here: it has not been measured.
     */
    public static String stuckStart(List<Supervisor.ThreadStack> stacks) {
        for (Supervisor.ThreadStack stack : stacks) {
            if (!stack.thread().equals("main") && !stack.thread().equals("Render thread")) continue;
            for (String frame : stack.frames()) {
                Matcher call = WINDOW_CALL.matcher(frame);
                if (call.find()) {
                    return "its " + stack.thread() + " is inside GLFW's glfw" + call.group(1) + ", so the window never"
                            + " finished opening. That is not a mod: under Xwayland, LWJGL's own GLFW can wait forever"
                            + " for a visibility event the compositor does not send, usually with the screen locked";
                }
            }
        }
        return null;
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
