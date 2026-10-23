package net.magicterra.stagewright.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import net.magicterra.stagewright.engine.supervise.AgentJar;
import net.magicterra.stagewright.engine.supervise.Narration;
import net.magicterra.stagewright.engine.supervise.Supervisor;

/**
 * Listens for the agent in each gate's game and ends the game when its agent says there is nothing
 * left to wait for.
 *
 * <p>The run task is a {@code JavaExec}, which waits for its process to exit. A suite that finished
 * leaves a game whose mods hold non-daemon threads, and the task then waits out its whole timeout; a
 * game that stopped moving costs the same. The agent in the game knows both, so this reads it from the
 * side and the run task never has to learn to.
 *
 * <p>A build service for the reason {@link StageWrightSideProcessService} is: Gradle closes it on
 * every exit path, so a listening socket and a monitor thread cannot outlive the build.
 *
 * <p>What it does not do is judge. The verdict task still reads the results file and is the only
 * verdict authority; the leaks recorded here are printed above it.
 */
public abstract class StageWrightSupervisionService
        implements BuildService<BuildServiceParameters.None>, AutoCloseable {

    static final int AGENT_GRACE_SECONDS = 30;
    static final int AGENT_IDLE_SECONDS = 20;
    /** Counted from when the supervisor opens, so it includes the JVM starting. */
    static final Duration CONNECT_LIMIT = Duration.ofMinutes(2);
    static final Duration HEARTBEAT_LIMIT = Duration.ofSeconds(60);
    /** After the agent says the run is over: its grace to end the JVM, then some for the kill to be unneeded. */
    static final Duration DRAIN = Duration.ofSeconds(AGENT_GRACE_SECONDS + 15);

    private static final Logger LOGGER = Logging.getLogger(StageWrightSupervisionService.class);

    /** One gate's supervisor and the thread that watches its game. */
    private record Watch(Supervisor supervisor, Thread monitor) {}

    private final Map<String, Watch> watches = new LinkedHashMap<>();
    private final Map<String, List<Supervisor.Leak>> leaks = new LinkedHashMap<>();

    /**
     * Start listening for one run and return the JVM argument that attaches the agent.
     *
     * @param key       the run, as {@link #leaks} will be asked for it
     * @param label     the topology, for the lines this prints
     * @param half      what the JVM is called in them: {@code game}, or {@code client} for a companion
     * @param artifacts where the agent jar is written
     * @param stall     how long nothing the game reports may move; zero turns the check off
     */
    synchronized String begin(String key, String label, String half, Path artifacts, Duration stall) {
        end(key);
        Path agent = AgentJar.stage(StageWrightSupervisionService.class, artifacts);
        Supervisor supervisor;
        try {
            supervisor = Supervisor.open();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot listen for the supervision agent", e);
        }
        Thread monitor = new Thread(() -> watch(supervisor, key, label, half, stall), "stagewright-watch-" + label);
        monitor.setDaemon(true);
        monitor.start();
        watches.put(key, new Watch(supervisor, monitor));
        leaks.remove(key);
        return supervisor.agentArgument(agent, AGENT_GRACE_SECONDS, AGENT_IDLE_SECONDS);
    }

    /** What held a finished JVM up in this run's game. Empty when it ended itself, or never ran. */
    synchronized List<Supervisor.Leak> leaks(String key) {
        Watch watch = watches.get(key);
        return watch == null ? leaks.getOrDefault(key, List.of()) : watch.supervisor().leaks();
    }

    private void watch(Supervisor supervisor, String key, String label, String half, Duration stall) {
        String tag = "[stagewright:" + label + "] ";
        try {
            ProcessHandle game = awaitGame(supervisor);
            if (game == null) {
                if (supervisor.agentPid() < 0 && !Thread.currentThread().isInterrupted()) {
                    // Not a kill: the argument passed through a run task this plugin does not own, and
                    // a launcher that drops it leaves the game well, only unsupervised.
                    LOGGER.warn(tag + "the " + half + " never reached its supervision agent in "
                            + CONNECT_LIMIT.toSeconds() + "s, so it is not supervised: a launch that"
                            + " drops JVM arguments would do it. The task's own timeout still applies.");
                }
                return;
            }
            Supervisor.Limits limits = new Supervisor.Limits(CONNECT_LIMIT, HEARTBEAT_LIMIT,
                    stall.isZero() ? Duration.ofDays(3650) : stall, Duration.ofDays(3650));
            Supervisor.Ended ended = supervisor.await(game, limits);
            switch (ended) {
                case DONE -> {
                    if (!supervisor.awaitExit(game, DRAIN)) {
                        LOGGER.error(tag + "the " + half + " finished and its agent could not end the JVM within "
                                + DRAIN.toSeconds() + "s. Killed.");
                        Supervisor.kill(game);
                    }
                }
                case HEARTBEAT_LOST -> kill(supervisor, game, tag,
                        Narration.jvmStopped(half, HEARTBEAT_LIMIT, supervisor.phase(), supervisor.ticks()));
                case CRASHED -> kill(supervisor, game, tag, "the " + half + " crashed and its JVM is still up; the"
                        + " report is at " + supervisor.crashReport());
                case STALLED -> kill(supervisor, game, tag, Narration.noProgress(half, (int) stall.toMinutes(),
                        supervisor.phase(), supervisor.ticks(), null, Narration.stuckStart(supervisor.stacks())));
                default -> {
                    // PROCESS_DIED: the task sees it. TIMED_OUT and NEVER_CONNECTED cannot be reached with these limits.
                }
            }
            synchronized (this) {
                leaks.put(key, supervisor.leaks());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Wait for the agent to say whose JVM it is, and hand back that process. Null if it never does. */
    private static ProcessHandle awaitGame(Supervisor supervisor) throws InterruptedException {
        long deadline = System.nanoTime() + CONNECT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            long pid = supervisor.agentPid();
            if (pid >= 0) return ProcessHandle.of(pid).orElse(null);
            Thread.sleep(250);
        }
        return null;
    }

    private static void kill(Supervisor supervisor, ProcessHandle game, String tag, String why)
            throws InterruptedException {
        LOGGER.error(tag + why);
        Narration.stacks(supervisor.stacks()).forEach(line -> LOGGER.error(tag + line));
        Supervisor.kill(game);
    }

    private void end(String key) {
        Watch old = watches.remove(key);
        if (old == null) return;
        leaks.put(key, old.supervisor().leaks());
        old.monitor().interrupt();
        old.supervisor().close();
    }

    @Override
    public synchronized void close() {
        for (String key : List.copyOf(watches.keySet())) end(key);
        leaks.clear();
    }
}
