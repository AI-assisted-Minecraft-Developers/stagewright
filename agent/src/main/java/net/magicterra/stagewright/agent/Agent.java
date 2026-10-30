package net.magicterra.stagewright.agent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports on this JVM to the process that started it, from its first instant to its last, and ends it once the run is
 * over. See {@code docs/design/supervision.md} for why this lives inside the game instead of watching it from outside,
 * and {@code docs/reference/supervision-protocol.md} for what it says.
 *
 * <p>Loaded with {@code -javaagent:<jar>=<host>:<port>:<token>[,grace=<seconds>]}. Anything wrong with that argument is
 * reported to standard error and leaves the JVM exactly as it would have been without the agent: this must never be the
 * reason a game does not start.
 */
public final class Agent {

    static final String AGENT_VERSION = "1";
    private static final long HEARTBEAT_MS = 1_000;
    private static final int CONNECT_ATTEMPTS = 40;
    private static final long CONNECT_RETRY_MS = 250;
    private static final int STACK_FRAMES = 12;
    private static final int IDLE_FRAMES = 8;

    private static volatile Agent running;

    private final Config config;
    private final Instrumentation instrumentation;
    private final Object wake = new Object();

    // Never read again: holding it keeps the connection reachable for the life of the agent.
    @SuppressWarnings("PMD.UnusedPrivateField")
    private volatile Socket socket;

    private volatile BufferedWriter out;
    private volatile String phase = "starting";
    private volatile long ticks;
    private volatile long doneAtNanos;
    private volatile ThreadOrigins origins;
    /** Listed in premain: what is already there then is a previous run's, and must not read as a crash. */
    private final CrashReports crashes = CrashReports.beside(Path.of(System.getProperty("user.dir", ".")));
    // Only the agent's own thread touches these.
    private String lastSignature = "";
    private long lastChangeNanos = System.nanoTime();

    private Agent(Config config, Instrumentation instrumentation) {
        this.config = config;
        this.instrumentation = instrumentation;
    }

    static Agent running() {
        return running;
    }

    /**
     * The JVM calls this before {@code main} with the {@code -javaagent} options. A bad option is reported on standard
     * error and the JVM starts unobserved.
     */
    // Standard error is the only channel open before the supervisor connects, and the game's log reads it.
    @SuppressWarnings("PMD.SystemPrintln")
    public static void premain(String args, Instrumentation instrumentation) {
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[stagewright-agent] not started: " + e.getMessage());
            return;
        }
        Agent agent = new Agent(config, instrumentation);
        synchronized (Agent.class) {
            if (running != null) {
                return;
            }
            running = agent;
        }
        // Here and not on the agent's thread: a pool a launcher starts in the JVM's first second is
        // exactly the kind that outlives the game, and a recording that begins after it names no one.
        // The cost is a few hundred milliseconds before main, against a start measured in tens of seconds.
        try {
            agent.origins = ThreadOrigins.start();
        } catch (Throwable unavailable) {
            // A JVM without jdk.jfr fails to link ThreadOrigins itself, which start() cannot catch.
            agent.origins = null;
        }
        Thread thread = new Thread(agent::run, "stagewright-agent");
        // Daemon: the one thread here must never be what keeps a finished JVM alive.
        thread.setDaemon(true);
        thread.start();
    }

    void phase(String name) {
        if (name == null || name.equals(phase)) {
            return;
        }
        phase = name;
        send(Lines.event("phase").text("phase", name));
    }

    void ticks(long value) {
        ticks = value;
    }

    void done() {
        synchronized (wake) {
            if (doneAtNanos != 0) {
                return;
            }
            doneAtNanos = System.nanoTime();
            wake.notifyAll();
        }
        send(Lines.event("done"));
    }

    /** One event of the in-game test run, already a JSON object; passed on as the line's {@code data}. */
    void test(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        send(Lines.event("test").raw("data", json));
    }

    private void run() {
        if (!connect()) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> send(Lines.event("exiting")), "stagewright-agent-exit"));
        send(Lines.event("hello")
                .text("token", config.token())
                .number("pid", ProcessHandle.current().pid())
                .text("agent", AGENT_VERSION)
                .text("java", System.getProperty("java.version", "")));
        while (true) {
            send(heartbeat());
            // A crashed game frequently does not exit, so this is how its supervisor stops waiting on it.
            for (String report : crashes.fresh()) {
                send(Lines.event("crash").text("report", report));
            }
            ThreadOrigins recording = origins;
            if (recording != null && recording.releaseIfAlone()) {
                origins = null;
            }
            long doneAt = doneAtNanos;
            if (doneAt != 0 && System.nanoTime() - doneAt >= config.graceMillis() * 1_000_000L) {
                endJvm();
                return;
            }
            synchronized (wake) {
                try {
                    wake.wait(HEARTBEAT_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    // The candidate is closed on failure, and kept as the live socket on success.
    @SuppressWarnings({"PMD.CloseResource", "PMD.SystemPrintln"})
    private boolean connect() {
        for (int attempt = 0; attempt < CONNECT_ATTEMPTS; attempt++) {
            Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress(config.host(), config.port()), 1_000);
                candidate.setTcpNoDelay(true);
                out = new BufferedWriter(new OutputStreamWriter(candidate.getOutputStream(), StandardCharsets.UTF_8));
                socket = candidate;
                return true;
            } catch (IOException e) {
                try {
                    candidate.close();
                } catch (IOException ignored) {
                    // Nothing was opened.
                }
                try {
                    Thread.sleep(CONNECT_RETRY_MS);
                } catch (InterruptedException interrupted) {
                    return false;
                }
            }
        }
        System.err.println("[stagewright-agent] cannot reach the supervisor at " + config.host() + ":" + config.port()
                + "; running unsupervised");
        return false;
    }

    private Lines heartbeat() {
        long classes = -1;
        try {
            classes = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        } catch (Throwable ignored) {
            // No java.management in this layer: the heartbeat is still a heartbeat.
        }
        Lines line = Lines.event("heartbeat")
                .number("atMs", System.currentTimeMillis())
                .text("phase", phase)
                .number("ticks", ticks);
        if (classes >= 0) {
            line.number("classes", classes);
        }

        // Where the threads that matter are, once nothing the supervisor can see has moved for a while:
        // the evidence it needs to say what a stuck game is stuck on, which it cannot read from outside.
        long now = System.nanoTime();
        String signature = phase + "|" + ticks + "|" + classes;
        if (signature.equals(lastSignature)) {
            if (now - lastChangeNanos >= config.idleMillis() * 1_000_000L) {
                line.objects("stacks", sampleStacks());
            }
        } else {
            lastSignature = signature;
            lastChangeNanos = now;
        }
        return line;
    }

    /** The game's own threads by name; the JDK's and the libraries' helpers would only bury them. */
    private List<Lines> sampleStacks() {
        List<Lines> sampled = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> entry :
                Thread.getAllStackTraces().entrySet()) {
            String name = entry.getKey().getName();
            if (!name.equals("main") && !name.equals("Render thread") && !name.equals("Server thread")) {
                continue;
            }
            List<String> frames = new ArrayList<>();
            StackTraceElement[] stack = entry.getValue();
            for (int i = 0; i < Math.min(stack.length, IDLE_FRAMES); i++) {
                frames.add(stack[i].toString());
            }
            sampled.add(new Lines().text("name", name).texts("stack", frames));
        }
        return sampled;
    }

    /** One line out. A broken connection ends the sending and nothing else: the game carries on. */
    // The writer wraps the connection that connect() opened, which is closed with the agent.
    @SuppressWarnings("PMD.CloseResource")
    private synchronized void send(Lines line) {
        BufferedWriter writer = out;
        if (writer == null) {
            return;
        }
        try {
            writer.write(line.build());
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            out = null;
        }
    }

    /**
     * The grace period is over and the JVM is still here: say what is holding it, then end it. The results are already
     * complete — that is what {@code done} means — so nothing is lost.
     */
    private void endJvm() {
        send(Lines.event("leaked").number("graceMs", config.graceMillis()).objects("threads", describeLiveThreads()));
        // halt, not exit: shutdown hooks run on a JVM that did not stop when asked, and a mod's hook
        // may be the thing that is waiting.
        Runtime.getRuntime().halt(0);
    }

    private List<Lines> describeLiveThreads() {
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        List<Thread> holders = new ArrayList<>();
        Set<String> classNames = new HashSet<>();
        ThreadOrigins recorded = origins;
        for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
            Thread thread = entry.getKey();
            if (thread.isDaemon() || !thread.isAlive() || thread.equals(Thread.currentThread())) {
                continue;
            }
            // The thread the JVM parks to wait for the others: it is the symptom, not a holder.
            if (thread.getName().equals("DestroyJavaVM")) {
                continue;
            }
            // The recorder's own reader: it holds nothing the game left behind.
            if (isRecorderThread(thread.getName())) {
                continue;
            }
            holders.add(thread);
            StackTraceElement[] stack = entry.getValue();
            for (int i = 0; i < Math.min(stack.length, STACK_FRAMES); i++) {
                classNames.add(stack[i].getClassName());
            }
            if (recorded != null) {
                for (String frame : recorded.of(thread.threadId())) {
                    classNames.add(typeOf(frame));
                }
            }
        }
        Map<String, String> jars = locate(classNames);
        List<Lines> described = new ArrayList<>();
        for (Thread thread : holders) {
            StackTraceElement[] stack = all.get(thread);
            List<String> frames = new ArrayList<>();
            Set<String> where = new LinkedHashSet<>();
            for (int i = 0; i < Math.min(stack.length, STACK_FRAMES); i++) {
                frames.add(stack[i].toString());
                String location = jars.get(stack[i].getClassName());
                if (location != null) {
                    where.add(location);
                }
            }
            List<String> started = recorded == null ? List.of() : recorded.of(thread.threadId());
            Set<String> startedIn = new LinkedHashSet<>();
            for (String frame : started) {
                String location = jars.get(typeOf(frame));
                if (location != null) {
                    startedIn.add(location);
                }
            }
            described.add(new Lines()
                    .text("name", thread.getName())
                    .texts("stack", frames)
                    .texts("jars", new ArrayList<>(where))
                    .texts("origin", started)
                    .texts("originJars", new ArrayList<>(startedIn)));
        }
        return described;
    }

    /**
     * A thread of the recorder behind {@link ThreadOrigins}; the first is not a daemon. Here and not there, because
     * asking must not link a class that needs {@code jdk.jfr}.
     */
    static boolean isRecorderThread(String name) {
        return name.startsWith("JFR ");
    }

    /** The class of a {@code class.method} frame. */
    private static String typeOf(String frame) {
        int dot = frame.lastIndexOf('.');
        return dot < 0 ? frame : frame.substring(0, dot);
    }

    /** Where each named class was loaded from. The JDK's own classes have no code source and are left out. */
    private Map<String, String> locate(Set<String> names) {
        Map<String, String> found = new HashMap<>();
        if (names.isEmpty()) {
            return found;
        }
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (!names.contains(type.getName())) {
                continue;
            }
            try {
                CodeSource source = type.getProtectionDomain().getCodeSource();
                URL location = source == null ? null : source.getLocation();
                if (location != null) {
                    found.putIfAbsent(type.getName(), location.toString());
                }
            } catch (SecurityException ignored) {
                // Leave it unlocated rather than fail the report.
            }
        }
        return found;
    }

    record Config(String host, int port, String token, long graceMillis, long idleMillis) {

        private static final String QUOTE = "'";

        static Config parse(String args) {
            if (args == null || args.isBlank()) {
                throw new IllegalArgumentException("expected <host>:<port>:<token>[,grace=<s>][,idle=<s>]");
            }
            String[] parts = args.split(",");
            String[] address = parts[0].split(":", 3);
            if (address.length != 3 || address[0].isEmpty() || address[2].isEmpty()) {
                throw new IllegalArgumentException("expected <host>:<port>:<token>, not " + QUOTE + parts[0] + QUOTE);
            }
            int port;
            try {
                port = Integer.parseInt(address[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("port is not a number: " + QUOTE + address[1] + QUOTE, e);
            }
            long grace = 30;
            long idle = 20;
            for (int i = 1; i < parts.length; i++) {
                String option = parts[i].trim();
                if (option.startsWith("grace=")) {
                    grace = seconds(option, "grace=");
                } else if (option.startsWith("idle=")) {
                    idle = seconds(option, "idle=");
                } else {
                    throw new IllegalArgumentException("unknown option " + QUOTE + option + QUOTE);
                }
            }
            return new Config(address[0], port, address[2], grace * 1_000, idle * 1_000);
        }

        private static long seconds(String option, String prefix) {
            try {
                return Long.parseLong(option.substring(prefix.length()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        prefix.substring(0, prefix.length() - 1) + " is not a number of seconds: " + QUOTE + option
                                + QUOTE,
                        e);
            }
        }
    }
}
