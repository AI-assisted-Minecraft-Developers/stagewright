package net.magicterra.stagewright.engine.supervise;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.magicterra.stagewright.engine.Json;

/**
 * Listens for the agent in a game process and answers, from what it says, the questions a launcher used to answer by
 * reading files from outside: is the run over, is the game still alive, and why has it not exited. See
 * {@code docs/design/supervision.md}; the wire format is in {@code docs/reference/supervision-protocol.md}.
 *
 * <p>One supervisor serves one game process. It binds {@code 127.0.0.1} on a port of its own choosing before the game
 * starts and tells the game the address and a one-time token, so a game left over from an earlier run cannot report
 * into this one.
 *
 * <p>Nothing here decides whether a test passed — only whether the game is done, and what to say when it stopped before
 * it was.
 */
public final class Supervisor implements AutoCloseable {

    /** How a wait for the end of a run ended. None of them is interchangeable with another. */
    public enum Ended {
        /** The game said its results are complete. */
        DONE,
        /** The process is gone and never said it was done. */
        PROCESS_DIED,
        /** Connected, then silent for longer than the limit: the whole JVM has stopped. */
        HEARTBEAT_LOST,
        /** The process is alive and never connected: it did not get as far as the agent. */
        NEVER_CONNECTED,
        /**
         * Alive and heartbeating, and nothing it reports has changed for longer than the limit: not its phase, not the
         * server ticks delivered, not the count of classes loaded. Loading a pack moves the last, running a suite moves
         * the second, so a game that moves none is stuck.
         */
        STALLED,
        /** The agent reported a crash report written since it started, or the caller's probe said so. */
        CRASHED,
        /** Alive and talking, and not done within the ceiling. */
        TIMED_OUT
    }

    /**
     * A thread that kept the JVM up after the run was over, the jars its stack runs through, and who started it. The
     * stack of a parked pool worker is all JDK; {@code origin} is the code that started the thread (outside the JDK,
     * innermost first) and {@code originJars} where that code came from. Both are empty when the JVM could not say.
     */
    public record Leak(
            String thread, List<String> stack, List<String> jars, List<String> origin, List<String> originJars) {
        public Leak(String thread, List<String> stack, List<String> jars) {
            this(thread, stack, jars, List.of(), List.of());
        }
    }

    /** Where a thread was when the game stopped moving. */
    public record ThreadStack(String thread, List<String> frames) {}

    /** The waits {@link #await} applies. */
    public record Limits(Duration connect, Duration heartbeat, Duration stall, Duration total) {}

    /** How a wait on a game and the companion joined to it ended, and whether it was the companion that ended it. */
    public record Ending(Ended ended, boolean companion) {}

    private static final long POLL_MS = 250;
    /** After the process is gone, how long to let its last lines be read before concluding it said nothing. */
    private static final long DRAIN_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final ServerSocket server;
    private final String token;
    private final Object lock = new Object();

    private boolean connected;
    private boolean connectionClosed;
    private boolean done;
    private boolean exiting;
    private boolean forcedDown;
    private String crashReport;
    private long agentPid = -1;
    private long lastBeatNanos;
    private long ticks;
    private long classes = -1;
    private long lastProgressNanos;
    private long exitSeenNanos;
    private final long openedNanos = System.nanoTime();
    private List<ThreadStack> stacks = List.of();
    private String phase = "";
    private final List<Leak> leaks = new ArrayList<>();

    private Supervisor(ServerSocket server, String token) {
        this.server = server;
        this.token = token;
    }

    /** Bind the loopback interface on a free port and start listening. */
    public static Supervisor open() throws IOException {
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        Supervisor supervisor = new Supervisor(server, HexFormat.of().formatHex(random));
        Thread acceptor = new Thread(supervisor::acceptLoop, "stagewright-supervisor");
        acceptor.setDaemon(true);
        acceptor.start();
        return supervisor;
    }

    public int port() {
        return server.getLocalPort();
    }

    /** For the tests that play the agent by hand. */
    String token() {
        return token;
    }

    /**
     * The JVM argument that attaches the agent to a game and points it back here. It goes where a {@code -D} goes:
     * before the loader's argument file, where the JVM rather than the launcher reads it.
     */
    public String agentArgument(Path agentJar, int graceSeconds) {
        return "-javaagent:" + agentJar.toAbsolutePath() + "="
                + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port() + ":" + token + ",grace="
                + graceSeconds;
    }

    /**
     * As above, and the agent starts attaching thread stacks to its heartbeats after {@code idleSeconds} without
     * movement.
     */
    public String agentArgument(Path agentJar, int graceSeconds, int idleSeconds) {
        return agentArgument(agentJar, graceSeconds) + ",idle=" + idleSeconds;
    }

    /**
     * Wait until the run is over, or cannot become over.
     *
     * <p>Whether the game said it was done is checked before whether it is alive: a game that says so and exits in the
     * same second is done, not dead.
     */
    public Ended await(ProcessHandle game, Limits limits) throws InterruptedException {
        return await(game, limits, () -> null);
    }

    /**
     * As {@link #await(ProcessHandle, Limits)}, asking {@code probe} at every poll for an ending the supervisor cannot
     * see itself. It is asked after {@code DONE} and before the rest, so a crash report written while the run was still
     * going outranks a process that has since gone.
     */
    public Ended await(ProcessHandle game, Limits limits, Supplier<Ended> probe) throws InterruptedException {
        game.onExit().thenRun(this::wake);
        synchronized (lock) {
            while (true) {
                if (done) return Ended.DONE;
                Ended external = probe.get();
                if (external != null) return external;
                Ended ended = evaluate(game, limits);
                if (ended != null) return ended;
                lock.wait(POLL_MS);
            }
        }
    }

    /**
     * Wait until this supervisor's game is done, or it or the companion joined to it, which {@code joined} supervises,
     * cannot carry on. The run is the game's: the companion only ever ends the wait as a failure, and its {@code DONE}
     * is one too, since a client that says it is done before the run is over has left the server.
     *
     * <p>A companion's ending is answered only once the game has not ended either within the drain that follows: a
     * server that crashes or halts drops its client, and then the server's ending is the cause and the answer.
     */
    public Ending await(
            ProcessHandle game, Limits limits, Supervisor joined, ProcessHandle companion, Limits companionLimits)
            throws InterruptedException {
        game.onExit().thenRun(this::wake);
        companion.onExit().thenRun(this::wake);
        while (true) {
            Ended own = peek(game, limits);
            if (own != null) return new Ending(own, false);
            Ended theirs = joined.peek(companion, companionLimits);
            if (theirs != null) {
                long deadline = System.nanoTime() + DRAIN_NANOS;
                while (System.nanoTime() < deadline) {
                    own = peek(game, limits);
                    if (own != null) return new Ending(own, false);
                    synchronized (lock) {
                        lock.wait(POLL_MS);
                    }
                }
                return new Ending(theirs, true);
            }
            synchronized (lock) {
                lock.wait(POLL_MS);
            }
        }
    }

    /**
     * What {@link #await} would answer right now, or null when the run is still going. For a caller watching more than
     * one game from one loop, such as a server and the client joined to it. Measured from when this supervisor was
     * opened, which is when the game was started.
     */
    public Ended peek(ProcessHandle game, Limits limits) {
        synchronized (lock) {
            return evaluate(game, limits);
        }
    }

    /** The endings the supervisor can see for itself. The lock is held. */
    private Ended evaluate(ProcessHandle game, Limits limits) {
        if (done) return Ended.DONE;
        // Before the process is asked about: a crashed game often stays up, and one that has gone since
        // still crashed rather than merely died.
        if (crashReport != null) return Ended.CRASHED;
        long now = System.nanoTime();
        if (!game.isAlive()) {
            // Its last lines may still be in flight. Once the connection has closed they have all been
            // read; a game that never connected has none to wait for.
            if (exitSeenNanos == 0) exitSeenNanos = now;
            if (!connected || connectionClosed || now - exitSeenNanos >= DRAIN_NANOS) return Ended.PROCESS_DIED;
        } else if (!connected && now - openedNanos >= nanos(limits.connect())) {
            return Ended.NEVER_CONNECTED;
        } else if (connected && now - lastBeatNanos >= nanos(limits.heartbeat())) {
            return Ended.HEARTBEAT_LOST;
        } else if (connected && now - lastProgressNanos >= nanos(limits.stall())) {
            return Ended.STALLED;
        }
        return now - openedNanos >= nanos(limits.total()) ? Ended.TIMED_OUT : null;
    }

    /** {@code Duration.toNanos} throws past about 292 years, which is what a huge --timeout asks for. */
    private static long nanos(Duration limit) {
        try {
            return limit.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * After {@link Ended#DONE}: wait for the process to end by itself or by the agent. False when it is still there at
     * the end of {@code wait}, and the caller should {@link #kill} it.
     */
    public boolean awaitExit(ProcessHandle game, Duration wait) throws InterruptedException {
        try {
            game.onExit().get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            if (game.isAlive()) return false;
        }
        // The agent's last line, `leaked`, goes out just before it halts the JVM: read what is left of the
        // connection, so that leaks() and forcedDown() answer for the whole run.
        synchronized (lock) {
            long deadline = System.nanoTime() + DRAIN_NANOS;
            while (connected && !connectionClosed) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                TimeUnit.NANOSECONDS.timedWait(lock, left);
            }
        }
        return true;
    }

    /** The threads that held the JVM up after done, as the agent reported them. Empty when it ended by itself. */
    public List<Leak> leaks() {
        synchronized (lock) {
            return List.copyOf(leaks);
        }
    }

    /**
     * Whether the agent had to end the JVM: it was still up when the grace period after {@code done} ran out. True even
     * when {@link #leaks()} is empty, which it is when what held the JVM up was not a thread.
     */
    public boolean forcedDown() {
        synchronized (lock) {
            return forcedDown;
        }
    }

    /**
     * Where the game's own threads were at the latest heartbeat, which the agent only attaches once nothing has moved
     * for a while. Empty while the game is moving.
     */
    public List<ThreadStack> stacks() {
        synchronized (lock) {
            return stacks;
        }
    }

    public String phase() {
        synchronized (lock) {
            return phase;
        }
    }

    public long ticks() {
        synchronized (lock) {
            return ticks;
        }
    }

    /** The first crash report the game wrote after its agent started, or null. */
    public String crashReport() {
        synchronized (lock) {
            return crashReport;
        }
    }

    /** The pid the agent reported, or -1 before it has. */
    public long agentPid() {
        synchronized (lock) {
            return agentPid;
        }
    }

    /**
     * Stop a game and everything it started.
     *
     * <p>The descendants are collected before the parent dies, because once it does the tree is no longer walkable from
     * here — and a child left behind holds the port, so the next run on that box fails to bind and reads as a bug in
     * the pack rather than as yesterday's run never having ended.
     */
    public static void kill(ProcessHandle game) throws InterruptedException {
        if (!game.isAlive()) return;
        List<ProcessHandle> descendants = game.descendants().toList();
        game.destroy();
        try {
            game.onExit().get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            game.destroyForcibly();
        }
        for (ProcessHandle child : descendants) {
            if (child.isAlive()) child.destroyForcibly();
        }
        try {
            game.onExit().get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException ignored) {
            // Nothing further this process can do about it.
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // Closing a listening socket that is already gone.
        }
    }

    private void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread reader = new Thread(() -> read(socket), "stagewright-supervisor-reader");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private volatile Consumer<Map<String, Object>> testListener = event -> {};

    /**
     * Receive each {@code test} event's {@code data}, in the order the game sent them, on the thread that reads the
     * game's connection. Set it before the game starts; an event that arrives earlier is not kept.
     */
    public void onTest(Consumer<Map<String, Object>> listener) {
        testListener = listener;
    }

    @SuppressWarnings("unchecked")
    private void read(Socket socket) {
        boolean mine = false;
        try (socket;
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                Map<String, Object> event = parse(line);
                if (event == null) continue;
                if (!mine) {
                    // The first line must be a hello that carries our token, and only one game counts.
                    if (!"hello".equals(event.get("event")) || !token.equals(event.get("token"))) return;
                    synchronized (lock) {
                        if (connected) return;
                        connected = true;
                    }
                    mine = true;
                }
                apply(event);
                // Outside the lock and on this one reader thread: in the order the game sent them, and a
                // listener that asks this supervisor something cannot deadlock against apply().
                if ("test".equals(event.get("event")) && event.get("data") instanceof Map<?, ?> data) {
                    try {
                        testListener.accept((Map<String, Object>) data);
                    } catch (RuntimeException e) {
                        // A listener's own failure is its own; the game's supervision carries on.
                    }
                }
            }
        } catch (IOException ignored) {
            // A reset connection is a game that went away; the state below says so.
        } finally {
            if (mine) {
                synchronized (lock) {
                    connectionClosed = true;
                    lock.notifyAll();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String line) {
        try {
            return Json.parse(line) instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void apply(Map<String, Object> event) {
        synchronized (lock) {
            long now = System.nanoTime();
            lastBeatNanos = now;
            switch (String.valueOf(event.get("event"))) {
                case "hello" -> {
                    if (event.get("pid") instanceof Number n) agentPid = n.longValue();
                    lastProgressNanos = now;
                }
                case "heartbeat" -> {
                    boolean moved = false;
                    if (event.get("phase") instanceof String s && !s.equals(phase)) {
                        phase = s;
                        moved = true;
                    }
                    if (event.get("ticks") instanceof Number n && n.longValue() != ticks) {
                        ticks = n.longValue();
                        moved = true;
                    }
                    if (event.get("classes") instanceof Number n && n.longValue() != classes) {
                        classes = n.longValue();
                        moved = true;
                    }
                    if (moved) lastProgressNanos = now;
                    if (event.get("stacks") instanceof List<?> sampled) {
                        List<ThreadStack> latest = new ArrayList<>();
                        for (Object one : sampled) {
                            if (one instanceof Map<?, ?> t) {
                                latest.add(new ThreadStack(String.valueOf(t.get("name")), strings(t.get("stack"))));
                            }
                        }
                        stacks = latest;
                    } else {
                        stacks = List.of();
                    }
                }
                case "phase" -> {
                    if (event.get("phase") instanceof String s && !s.equals(phase)) {
                        phase = s;
                        lastProgressNanos = now;
                    }
                }
                case "crash" -> {
                    if (crashReport == null) crashReport = String.valueOf(event.get("report"));
                }
                case "done" -> done = true;
                case "exiting" -> exiting = true;
                case "leaked" -> {
                    forcedDown = true;
                    if (event.get("threads") instanceof List<?> threads) {
                        for (Object thread : threads) {
                            if (!(thread instanceof Map<?, ?> t)) continue;
                            leaks.add(new Leak(
                                    String.valueOf(t.get("name")),
                                    strings(t.get("stack")),
                                    strings(t.get("jars")),
                                    strings(t.get("origin")),
                                    strings(t.get("originJars"))));
                        }
                    }
                }
                default -> {
                    // An event this version does not know is not an error: the agent may be newer.
                }
            }
            lock.notifyAll();
        }
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) out.add(String.valueOf(item));
        }
        return out;
    }

    /** Whether the agent said it was on its way out. Informational: the process is the authority on being gone. */
    public boolean exiting() {
        synchronized (lock) {
            return exiting;
        }
    }
}
