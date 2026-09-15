package net.magicterra.stagewright.harness;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the server thread spends its time while an arena PREP is stalled, appended to that ENV_FAIL.
 *
 * <p>{@link ArenaChunkReport} can say that the chunk executor holds tasks and that the server's ticks
 * take their whole budget, not what fills them. A daemon thread samples the server thread's stack every
 * {@link #INTERVAL_MS} ms, {@link #SAMPLES} times, and the report is the most frequent stacks, cut to the
 * frames nearest the leaf, with how many samples each took.
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

    private final Map<String, Integer> stacks = new ConcurrentHashMap<>();
    private final Thread sampler;
    private volatile int taken;

    private ServerThreadSampler(Thread target) {
        sampler = new Thread(() -> {
            for (int i = 0; i < SAMPLES && target.isAlive(); i++) {
                stacks.merge(signature(target.getStackTrace()), 1, Integer::sum);
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

    /** The sample count, then each of the most frequent stacks as {@code Nx leaf < caller < …}. */
    String describe() {
        List<Map.Entry<String, Integer>> top = stacks.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(STACKS_SHOWN)
                .toList();
        StringBuilder out = new StringBuilder().append(taken).append(" samples every ").append(INTERVAL_MS)
                .append(" ms");
        for (Map.Entry<String, Integer> e : top) out.append("; ").append(e.getValue()).append("x ").append(e.getKey());
        return out.toString();
    }

    private static String signature(StackTraceElement[] stack) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(DEPTH, stack.length); i++) {
            if (i > 0) b.append(" < ");
            String cls = stack[i].getClassName();
            b.append(cls, cls.lastIndexOf('.') + 1, cls.length()).append('.').append(stack[i].getMethodName());
        }
        return b.toString();
    }
}
