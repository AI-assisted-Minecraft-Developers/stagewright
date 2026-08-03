package net.magicterra.stagewright.harness;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.function.Supplier;

import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.scene.SceneOutcome;

/**
 * Names the scene that stopped the server tick, from a thread that is not the server tick.
 *
 * <p>Scene bodies run inline on the server thread, and that is deliberate — {@code ServerLevel} is
 * not thread-safe, so an assertion that reads the world from anywhere else is reading a race. The
 * price is that the harness shares a fate with the thing it measures: when a mod wedges the tick,
 * every mechanism the harness has stops with it. Scene budgets are counted in TICKS, so no budget
 * ever expires. Nothing more is written to the results file. The suite does not end.
 *
 * <p>What survives that is the layer outside: the orchestrator's wall-clock timeout kills the
 * process, and a results file with no {@code done} footer is read as RED — "the harness died
 * mid-run". So a hang is already impossible to mistake for a pass. What it is NOT is diagnosable.
 * You wait out the full timeout (45 minutes, in this project's suites) and learn that something
 * died somewhere. For a modpack author running 200 mods, the one fact worth having — which scene,
 * and what was on the stack — is exactly the fact that answer omits.
 *
 * <p>This thread supplies it. It holds no lock the tick needs and touches no game state; it watches
 * a counter, and when the counter stops it writes the verdict the wedged thread can no longer write:
 * the running scene as TIMEOUT with a thread dump in its reason, a matching footer, and then down.
 *
 * <h2>Why a stalled COUNTER and not a slow TICK</h2>
 *
 * <p>The trigger is "the tick counter has not moved in {@value #STALL_MS} ms", never "a tick took
 * longer than X". Single ticks legitimately run for seconds — a world save, a chunk generation
 * burst, a mod's registry pass on first load — and a threshold tight enough to catch a deadlock
 * quickly is far tighter than those. Killing a healthy run that was merely busy would be a worse
 * failure than the one this prevents, because it would be blamed on the mod under test.
 */
final class StallWatchdog {

    /** How long the tick counter may stand still before the run is declared wedged. */
    private static final long STALL_MS = 90_000;

    /** How often to look. Cheap: one volatile read and a clock read. */
    private static final long POLL_MS = 1_000;

    /** Stacks to print. Enough to see who holds what, short of dumping a 200-mod server whole. */
    private static final int DUMP_DEPTH = 24;

    private final ResultsJsonl out;
    private final Supplier<String> runningScene;
    private final Supplier<Long> tickCounter;
    private final Thread thread;
    private volatile boolean stopped;

    StallWatchdog(ResultsJsonl out, Supplier<Long> tickCounter, Supplier<String> runningScene) {
        this.out = out;
        this.tickCounter = tickCounter;
        this.runningScene = runningScene;
        this.thread = new Thread(this::watch, "stagewright-stall-watchdog");
        // Daemon: on a clean run this thread must never be the reason the JVM stays up. That is the
        // failure the exit watchdog exists to force past, and introducing another instance of it
        // here would be its own bug.
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    /** Stop watching — the suite finished on its own. */
    void stop() {
        stopped = true;
        thread.interrupt();
    }

    private void watch() {
        long lastSeen = tickCounter.get();
        long lastMovedMs = System.currentTimeMillis();
        while (!stopped) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long now = tickCounter.get();
            if (now != lastSeen) {
                lastSeen = now;
                lastMovedMs = System.currentTimeMillis();
                continue;
            }
            long stalledFor = System.currentTimeMillis() - lastMovedMs;
            if (stalledFor < STALL_MS) continue;
            if (stopped) return;
            reportStall(stalledFor);
            return;
        }
    }

    private void reportStall(long stalledForMs) {
        String scene = runningScene.get();
        String diagnosis = diagnose();
        String reason = "server tick has not advanced in " + (stalledForMs / 1000) + "s — the run is"
                + " wedged and no tick-counted budget can expire to end it. " + diagnosis;

        StageWrightCommon.LOG.error("[{}] STALLED during '{}': {}", StageWrightCommon.MOD_ID, scene, reason);

        try {
            // One monitor for both lines: the footer must not be able to land without the record it
            // terminates, or the orchestrator reports TRUNCATED and the stall goes unnamed.
            synchronized (out) {
                out.writeScene(scene, SceneOutcome.TIMEOUT, 0, stalledForMs, reason);
                out.writeDone(out.sceneRecordCount());
            }
        } catch (RuntimeException e) {
            // Writing failed, so the file cannot carry the verdict. Fall back to a non-zero exit,
            // which the orchestrator reads as a dead run rather than a green one.
            StageWrightCommon.LOG.error("[{}] could not write the stall verdict", StageWrightCommon.MOD_ID, e);
            Runtime.getRuntime().halt(2);
        }

        // halt, not exit: shutdown hooks run on a JVM whose main thread is wedged, and a mod hook
        // that waits on the server would hang here too. Zero because the results file carries the
        // verdict — the same contract the exit watchdog follows.
        Runtime.getRuntime().halt(0);
    }

    /**
     * What the JVM can still be asked while the tick is stuck: is this a deadlock, and if so between
     * whom — and failing that, what is the server thread actually doing.
     */
    private static String diagnose() {
        StringBuilder sb = new StringBuilder();
        try {
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            long[] deadlocked = mx.findDeadlockedThreads();
            if (deadlocked != null && deadlocked.length > 0) {
                sb.append("DEADLOCK between ").append(deadlocked.length).append(" threads: ");
                for (ThreadInfo info : mx.getThreadInfo(deadlocked, DUMP_DEPTH)) {
                    if (info == null) continue;
                    sb.append('[').append(info.getThreadName()).append(" waiting on ");
                    LockInfo lock = info.getLockInfo();
                    sb.append(lock == null ? "?" : lock.toString());
                    if (info.getLockOwnerName() != null) sb.append(" held by ").append(info.getLockOwnerName());
                    sb.append("] ");
                }
                sb.append("| ");
            }
            sb.append(serverThreadStack(mx));
        } catch (RuntimeException | LinkageError e) {
            // Diagnosis is a bonus; never let it cost the verdict itself.
            sb.append("(diagnosis unavailable: ").append(e).append(')');
        }
        return sb.toString();
    }

    private static String serverThreadStack(ThreadMXBean mx) {
        for (ThreadInfo info : mx.dumpAllThreads(false, false)) {
            if (info == null || !"Server thread".equals(info.getThreadName())) continue;
            StringBuilder sb = new StringBuilder("Server thread is ").append(info.getThreadState()).append(": ");
            StackTraceElement[] stack = info.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, DUMP_DEPTH); i++) {
                if (i > 0) sb.append(" <- ");
                sb.append(stack[i]);
            }
            return sb.toString();
        }
        return "no thread named 'Server thread' is alive — it died rather than hung";
    }
}
