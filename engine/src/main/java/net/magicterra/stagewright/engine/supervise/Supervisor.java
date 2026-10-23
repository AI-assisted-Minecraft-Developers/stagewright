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

import net.magicterra.stagewright.engine.Json;

/**
 * Listens for the agent in a game process and answers, from what it says, the questions a launcher
 * used to answer by reading files from outside: is the run over, is the game still alive, and why
 * has it not exited. See {@code docs/design/supervision.md}; the wire format is in
 * {@code docs/reference/supervision-protocol.md}.
 *
 * <p>One supervisor serves one game process. It binds {@code 127.0.0.1} on a port of its own choosing
 * before the game starts and tells the game the address and a one-time token, so a game left over
 * from an earlier run cannot report into this one.
 *
 * <p>The results file stays the only verdict authority. Nothing here decides whether a run passed —
 * only when to look at the file, and what to say when there is nothing to look at.
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
        /** Alive and talking, and not done within the ceiling. */
        TIMED_OUT
    }

    /** A thread that kept the JVM up after the run was over, and the jars its stack runs through. */
    public record Leak(String thread, List<String> stack, List<String> jars) {}

    /** The waits {@link #await} applies. */
    public record Limits(Duration connect, Duration heartbeat, Duration total) {}

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
    private long agentPid = -1;
    private long lastBeatNanos;
    private long ticks;
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
     * The JVM argument that attaches the agent to a game and points it back here. It goes where a
     * {@code -D} goes: before the loader's argument file, where the JVM rather than the launcher
     * reads it.
     */
    public String agentArgument(Path agentJar, int graceSeconds) {
        return "-javaagent:" + agentJar.toAbsolutePath() + "=" + InetAddress.getLoopbackAddress().getHostAddress()
                + ":" + port() + ":" + token + ",grace=" + graceSeconds;
    }

    /**
     * Wait until the run is over, or cannot become over.
     *
     * <p>Whether the game said it was done is checked before whether it is alive: a game that says so
     * and exits in the same second is done, not dead.
     */
    public Ended await(ProcessHandle game, Limits limits) throws InterruptedException {
        long start = System.nanoTime();
        game.onExit().thenRun(this::wake);
        long exitSeen = 0;
        synchronized (lock) {
            while (true) {
                if (done) return Ended.DONE;
                long now = System.nanoTime();
                if (!game.isAlive()) {
                    // Its last lines may still be in flight. Once the connection has closed they have all
                    // been read; a game that never connected has none to wait for.
                    if (exitSeen == 0) exitSeen = now;
                    if (!connected || connectionClosed || now - exitSeen >= DRAIN_NANOS) return Ended.PROCESS_DIED;
                } else if (!connected && now - start >= limits.connect().toNanos()) {
                    return Ended.NEVER_CONNECTED;
                } else if (connected && now - lastBeatNanos >= limits.heartbeat().toNanos()) {
                    return Ended.HEARTBEAT_LOST;
                }
                if (now - start >= limits.total().toNanos()) return Ended.TIMED_OUT;
                lock.wait(POLL_MS);
            }
        }
    }

    /**
     * After {@link Ended#DONE}: wait for the process to end by itself or by the agent. False when it
     * is still there at the end of {@code wait}, and the caller should {@link #kill} it.
     */
    public boolean awaitExit(ProcessHandle game, Duration wait) throws InterruptedException {
        try {
            game.onExit().get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return !game.isAlive();
        }
    }

    /** The threads that held the JVM up after done, as the agent reported them. Empty when it ended by itself. */
    public List<Leak> leaks() {
        synchronized (lock) {
            return List.copyOf(leaks);
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

    /** The pid the agent reported, or -1 before it has. */
    public long agentPid() {
        synchronized (lock) {
            return agentPid;
        }
    }

    /**
     * Stop a game and everything it started.
     *
     * <p>The descendants are collected before the parent dies, because once it does the tree is no
     * longer walkable from here — and a child left behind holds the port, so the next run on that box
     * fails to bind and reads as a bug in the pack rather than as yesterday's run never having ended.
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

    private void read(Socket socket) {
        boolean mine = false;
        try (socket; BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
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
                }
                case "heartbeat" -> {
                    if (event.get("phase") instanceof String s) phase = s;
                    if (event.get("ticks") instanceof Number n) ticks = n.longValue();
                }
                case "phase" -> {
                    if (event.get("phase") instanceof String s) phase = s;
                }
                case "done" -> done = true;
                case "exiting" -> exiting = true;
                case "leaked" -> {
                    if (event.get("threads") instanceof List<?> threads) {
                        for (Object thread : threads) {
                            if (!(thread instanceof Map<?, ?> t)) continue;
                            leaks.add(new Leak(String.valueOf(t.get("name")), strings(t.get("stack")),
                                    strings(t.get("jars"))));
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
