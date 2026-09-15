package net.magicterra.stagewright.harness;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the server thread spends its time while an arena PREP is stalled, appended to that ENV_FAIL.
 *
 * <p>{@link ArenaChunkReport} can say that the chunk executor holds tasks that never run and that the
 * server's ticks take their whole budget, not what fills them. A daemon thread samples the server
 * thread's stack every {@link #INTERVAL_MS} ms, {@link #SAMPLES} times, and the report is the most
 * frequent stacks, cut to the frames nearest the leaf, with how many samples each took.
 *
 * <p>The samples taken inside {@code ServerChunkCache.pollTask} are also counted apart, cut at that
 * frame. PREP runs the chunk executor through it for a slice of each tick, and a stalled arena whose
 * executor still held the same tasks after thousands of those polls needs to say where the polls went:
 * the executor's own tasks come only after the distance manager's updates, and a poll that finds
 * updates to run returns without touching them.
 *
 * <p>An instrument only: taking a stack pauses the sampled thread for an instant, and nothing else
 * changes.
 */
final class ServerThreadSampler {

    private static final int INTERVAL_MS = 5;
    /** Two seconds' worth, well inside the ticks a stall waits before PREP gives up. */
    private static final int SAMPLES = 400;
    private static final int DEPTH = 14;
    private static final int STACKS_SHOWN = 4;
    private static final int POLL_STACKS_SHOWN = 3;

    private final Map<String, Integer> stacks = new ConcurrentHashMap<>();
    private final Map<String, Integer> pollStacks = new ConcurrentHashMap<>();
    private final Thread sampler;
    private volatile int taken;
    private volatile int inPoll;

    private ServerThreadSampler(Thread target) {
        sampler = new Thread(() -> {
            for (int i = 0; i < SAMPLES && target.isAlive(); i++) {
                StackTraceElement[] stack = target.getStackTrace();
                stacks.merge(signature(stack, DEPTH), 1, Integer::sum);
                int poll = pollFrame(stack);
                if (poll >= 0) {
                    pollStacks.merge(signature(stack, poll + 1), 1, Integer::sum);
                    inPoll++;
                }
                taken++;
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "StageWright PREP sampler");
        sampler.setDaemon(true);
    }

    static ServerThreadSampler start(Thread target) {
        ServerThreadSampler s = new ServerThreadSampler(target);
        s.sampler.start();
        return s;
    }

    /** Stops {@code s} if there is one; always null, for the caller's field. */
    static ServerThreadSampler stop(ServerThreadSampler s) {
        if (s != null) s.sampler.interrupt();
        return null;
    }

    /**
     * The sample count, each of the most frequent stacks as {@code Nx leaf < caller < …}, then how many
     * samples were inside {@code ServerChunkCache.pollTask} and the most frequent of those.
     */
    String describe() {
        StringBuilder out = new StringBuilder().append(taken).append(" samples every ").append(INTERVAL_MS)
                .append(" ms");
        for (Map.Entry<String, Integer> e : top(stacks, STACKS_SHOWN)) {
            out.append("; ").append(e.getValue()).append("x ").append(e.getKey());
        }
        out.append(". Inside ServerChunkCache.pollTask: ").append(inPoll);
        for (Map.Entry<String, Integer> e : top(pollStacks, POLL_STACKS_SHOWN)) {
            out.append("; ").append(e.getValue()).append("x ").append(e.getKey());
        }
        return out.toString();
    }

    private static List<Map.Entry<String, Integer>> top(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(n)
                .toList();
    }

    /** The index of the outermost {@code ServerChunkCache.pollTask} frame, or -1. */
    private static int pollFrame(StackTraceElement[] stack) {
        for (int i = stack.length - 1; i >= 0; i--) {
            if (stack[i].getMethodName().equals("pollTask") && stack[i].getClassName().endsWith("ServerChunkCache")) {
                return i;
            }
        }
        return -1;
    }

    private static String signature(StackTraceElement[] stack, int depth) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(depth, stack.length); i++) {
            if (i > 0) b.append(" < ");
            String cls = stack[i].getClassName();
            b.append(cls, cls.lastIndexOf('.') + 1, cls.length()).append('.').append(stack[i].getMethodName());
        }
        return b.toString();
    }
}
