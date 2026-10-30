package net.magicterra.stagewright.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingStream;

/**
 * Who started each thread, from the JVM's own {@code jdk.ThreadStart} events.
 *
 * <p>A thread that holds a finished JVM up is nearly always parked in the JDK: a pool worker waiting on its queue, an
 * HTTP dispatcher waiting on a socket. Its stack says nothing about whose it is. The stack of whoever <i>started</i> it
 * does, and nothing else records that: the JVM keeps no creator for a thread, and a thread that is still running has
 * long since left the code that made it. JFR writes it down at the moment of the start.
 *
 * <p>Kept in its own class so that a JVM without {@code jdk.jfr} fails to link here, once, and the agent goes on
 * without origins instead of without itself. Only frames outside the JDK are kept, and a thread that ends is forgotten,
 * so what is held is bounded by the threads alive at once.
 */
final class ThreadOrigins {

    private static final int FRAMES = 8;
    private static final int CAP = 20_000;
    private static final String[] JDK = {"java.", "javax.", "jdk.", "sun.", "com.sun."};

    private final Map<Long, List<String>> origins = new ConcurrentHashMap<>();
    private final RecordingStream stream;

    private ThreadOrigins(RecordingStream stream) {
        this.stream = stream;
    }

    /** Start recording, or null when this JVM cannot. */
    // The stream is stored in the recorder and closed by releaseIfAlone(), not by this method.
    @SuppressWarnings("PMD.CloseResource")
    static ThreadOrigins start() {
        try {
            RecordingStream stream = new RecordingStream();
            ThreadOrigins recorder = new ThreadOrigins(stream);
            stream.enable("jdk.ThreadStart").withStackTrace();
            stream.enable("jdk.ThreadEnd");
            stream.onEvent("jdk.ThreadStart", event -> {
                if (event.getThread("eventThread") == null || recorder.origins.size() >= CAP) {
                    return;
                }
                List<String> frames = outsideTheJdk(event.getStackTrace());
                if (!frames.isEmpty()) {
                    recorder.origins.put(event.getThread("eventThread").getJavaThreadId(), frames);
                }
            });
            stream.onEvent("jdk.ThreadEnd", event -> {
                if (event.getThread("thread") != null) {
                    recorder.origins.remove(event.getThread("thread").getJavaThreadId());
                }
            });
            stream.startAsync();
            return recorder;
        } catch (Throwable unavailable) {
            // JFR missing, disabled, or refusing: leaks are reported without where they came from.
            return null;
        }
    }

    /** {@code class.method} for the frames that start a thread outside the JDK and this agent, innermost first. */
    private static List<String> outsideTheJdk(RecordedStackTrace trace) {
        List<String> frames = new ArrayList<>();
        if (trace == null) {
            return frames;
        }
        for (RecordedFrame frame : trace.getFrames()) {
            if (frames.size() >= FRAMES) {
                break;
            }
            if (frame.getMethod() == null || frame.getMethod().getType() == null) {
                continue;
            }
            String type = frame.getMethod().getType().getName();
            if (isJdk(type) || type.startsWith("net.magicterra.stagewright.agent.")) {
                continue;
            }
            frames.add(type + "." + frame.getMethod().getName());
        }
        return frames;
    }

    private static boolean isJdk(String type) {
        for (String prefix : JDK) {
            if (type.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stop recording once nothing but this recorder is keeping the JVM up, and say whether it did.
     *
     * <p>The stream's reader thread is not a daemon, so a game with the agent attached would never end by itself,
     * however cleanly it finished: the one thing this agent exists to cure, caused by the agent. Closing the stream
     * when every other non-daemon thread is gone lets the JVM do what it was about to. {@code DestroyJavaVM} is the JVM
     * waiting for exactly this, not a thread of the game's.
     */
    // The root group is the only way to see every thread, the JVM's included; nothing here changes a group.
    @SuppressWarnings("PMD.AvoidThreadGroup")
    boolean releaseIfAlone() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        Thread[] all = new Thread[root.activeCount() + 32];
        int count = root.enumerate(all, true);
        for (int i = 0; i < count; i++) {
            if (holdsJvmUp(all[i])) {
                return false;
            }
        }
        stream.close();
        return true;
    }

    /** Whether a thread keeps the JVM up: non-daemon, alive, and neither the JVM's own waiter nor a recorder reader. */
    private static boolean holdsJvmUp(Thread thread) {
        if (thread == null || thread.isDaemon() || !thread.isAlive()) {
            return false;
        }
        return !"DestroyJavaVM".equals(thread.getName()) && !Agent.isRecorderThread(thread.getName());
    }

    /** The frames that started the thread with this id, or empty when it was not seen starting. */
    List<String> of(long threadId) {
        return origins.getOrDefault(threadId, List.of());
    }
}
