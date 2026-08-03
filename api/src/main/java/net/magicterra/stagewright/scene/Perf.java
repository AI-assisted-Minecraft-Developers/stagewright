package net.magicterra.stagewright.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Performance sampling for scenes, and the assertions that go with it.
 *
 * <p><b>Measure against a baseline taken in the same run, never against a constant.</b> A threshold
 * like "TPS at least 19" encodes the machine it was written on: it passes vacuously on a fast host
 * and fails spuriously on a loaded CI box, and either way the number stops meaning anything the
 * first time the hardware changes. What a scene actually wants to know is whether the feature under
 * test made things worse, which is a comparison between two windows measured minutes apart on the
 * same machine, in the same JVM, under the same GC:
 *
 * <pre>{@code
 * s.perf().sampleFor(60, baseline ->
 *     s.perf().afterLoading(() -> buildFiveHundredWaystones(s), 60, loaded -> {
 *         s.record("tps.base", baseline.tps());
 *         s.record("tps.loaded", loaded.tps());
 *         s.check(loaded.tpsDropVs(baseline)).as("TPS drop").isAtMost(0.05);
 *         s.check(loaded.heapGrowthVs(baseline)).as("heap growth (MiB)").isAtMost(256);
 *     }));
 * }</pre>
 *
 * <p><b>Sampling rides the tick the scene is already waiting on.</b> The window's await condition is
 * what takes each sample, so there is no extra hook, no background thread, and nothing added to the
 * server's tick path when no scene is measuring. That matters: an observer that costs tick time
 * changes the very number it reports.
 */
public final class Perf {

    private final SceneContext ctx;

    Perf(SceneContext ctx) {
        this.ctx = ctx;
    }

    /**
     * One measurement window.
     *
     * @param ticks       server ticks observed
     * @param wallMs      wall-clock span of the window
     * @param tps         ticks per second, as observed — capped at 20, which is the ceiling the
     *                    server paces itself to
     * @param msptMean    mean interval between ticks, in ms (≈50 on a healthy server)
     * @param msptP99     99th-percentile interval, in ms — the number a stutter shows up in while
     *                    the mean stays flat
     * @param heapMib     used heap at window close after a collection hint, in MiB — retained, not
     *                    peak, so a client's render churn does not read as a leak
     * @param cpuLoad     mean process CPU load in [0,1], or NaN where the JVM does not report it
     * @param fps         mean client frames per second across the window, or NaN when there is no
     *                    client in this JVM — i.e. on every dedicated server, including the server
     *                    half of the client-joins-server topology. Only the single-client topology
     *                    produces a number here, which is the reason that topology is worth running:
     *                    a render-side regression is invisible to every server-side measurement.
     */
    public record Window(int ticks, long wallMs, double tps, double msptMean, double msptP99,
                         double heapMib, double cpuLoad, double fps) {

        /** Fractional TPS drop relative to a baseline: 0.05 means "5% slower". Negative means this
         *  window ran FASTER, which happens routinely and is not a failure. */
        public double tpsDropVs(Window baseline) {
            if (baseline.tps() <= 0) return Double.NaN;
            return (baseline.tps() - tps) / baseline.tps();
        }

        /** Growth in RETAINED heap over a baseline, in MiB — still coarse, but it is a leak signal
         *  rather than an allocation-rate one. Reach for a real profiler before believing a small
         *  positive number; this is here to catch the order-of-magnitude regression. */
        public double heapGrowthVs(Window baseline) {
            return heapMib - baseline.heapMib();
        }

        /** Fractional FPS drop relative to a baseline: 0.05 means "5% fewer frames". NaN on a
         *  topology with no client, which is the honest answer rather than a passing zero — assert
         *  with {@code check}, or guard on {@link #hasFps()}, so a server run does not fail on a
         *  measurement it could never take. */
        public double fpsDropVs(Window baseline) {
            if (!(baseline.fps() > 0)) return Double.NaN;
            return (baseline.fps() - fps) / baseline.fps();
        }

        /** True when this window actually observed a client frame rate. */
        public boolean hasFps() {
            return !Double.isNaN(fps);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "%d ticks in %d ms: tps=%.2f mspt=%.1f/p99 %.1f heap=%.0fMiB cpu=%.2f fps=%.1f",
                    ticks, wallMs, tps, msptMean, msptP99, heapMib, cpuLoad, fps);
        }
    }

    /**
     * Sample the next {@code ticks} server ticks, then hand the window to {@code then}.
     *
     * <p>Registers a continuation, so it obeys the same rule as every other step: the scene's tick
     * budget must be large enough to contain it, or the scene TIMEOUTs before the callback runs.
     */
    public void sampleFor(int ticks, Consumer<Window> then) {
        Accumulator acc = new Accumulator();
        // The await CONDITION is the sampler. advance() evaluates the head step's condition exactly
        // once per tick, which is precisely the cadence we want, and it means an unmeasured scene
        // pays nothing at all.
        ctx.await(() -> {
            acc.sample();
            return acc.count >= ticks;
        }).within(ticks + 100).then(() -> then.accept(acc.close()));
    }

    /** Run {@code load} (synchronously, on this tick), then sample the following {@code ticks}. The
     *  ordering is the point: the load must be in place before the window opens, or the window
     *  averages the cost of building it together with the cost of having it. */
    public void afterLoading(Runnable load, int ticks, Consumer<Window> then) {
        load.run();
        sampleFor(ticks, then);
    }

    private final class Accumulator {
        private final List<Double> intervalsMs = new ArrayList<>();
        private long lastNanos;
        private long startNanos;
        private int count;
        // CPU as consumed TIME, differenced across the window, rather than the JVM's instantaneous
        // load reading. getProcessCpuLoad() is defined over the interval since its previous call,
        // and calling it every ~50 ms samples an interval too short to be meaningful — it reported a
        // flat 0.0 for a window in which the server was demonstrably working.
        private long cpuStartNanos = -1;
        private long cpuEndNanos = -1;
        private long fpsSum;
        private int fpsSamples;

        void sample() {
            long now = System.nanoTime();
            if (count == 0) {
                startNanos = now;
            } else {
                intervalsMs.add((now - lastNanos) / 1_000_000.0);
            }
            lastNanos = now;
            count++;

            long cpuNanos = processCpuTimeNanos();
            if (cpuNanos >= 0) {
                if (cpuStartNanos < 0) cpuStartNanos = cpuNanos;
                cpuEndNanos = cpuNanos;
            }

            int frames = clientFps();
            if (frames >= 0) {
                fpsSum += frames;
                fpsSamples++;
            }
        }

        Window close() {
            long wallMs = (lastNanos - startNanos) / 1_000_000L;
            double mean = 0;
            double p99 = 0;
            if (!intervalsMs.isEmpty()) {
                double sum = 0;
                for (double v : intervalsMs) sum += v;
                mean = sum / intervalsMs.size();
                List<Double> sorted = new ArrayList<>(intervalsMs);
                Collections.sort(sorted);
                p99 = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.99) - 1));
            }
            // Observed rate, then clamped: the server paces itself to 20 and a window that happens
            // to catch a catch-up burst can measure faster than that. Reporting 23 TPS would make a
            // baseline look better than reality and mask the very regression this exists to find.
            double tps = wallMs > 0 ? Math.min(20.0, (count - 1) * 1000.0 / wallMs) : 20.0;
            // Fraction of ONE core's wall time this process spent on CPU. Deliberately not divided
            // by the core count: a scene cares whether the server thread got busier, and dividing by
            // however many cores the host happens to have would make the same regression report a
            // different number on every machine — the exact machine-dependence baselining exists to
            // remove.
            double cpu = Double.NaN;
            if (cpuStartNanos >= 0 && cpuEndNanos > cpuStartNanos && wallMs > 0) {
                cpu = (cpuEndNanos - cpuStartNanos) / (wallMs * 1_000_000.0);
            }
            double fps = fpsSamples > 0 ? (double) fpsSum / fpsSamples : Double.NaN;
            return new Window(count, wallMs, tps, mean, p99, retainedHeapMib(), cpu, fps);
        }

        /**
         * Used heap after asking for a collection — what the window LEFT BEHIND, not what it churned
         * through.
         *
         * <p>This used to be peak used-heap sampled every tick, and on a dedicated server that was
         * close enough: the server thread allocates modestly and the sawtooth stayed small. Inside a
         * game client it is not close at all. The renderer allocates continuously, so the peak is
         * dominated by garbage that was never the mod's, and a scene placing 144 blocks measured
         * 391 MiB of "growth" against a 256 MiB budget — a red gate reporting the frame rate of the
         * machine it ran on rather than anything about the mod.
         *
         * <p>{@code System.gc()} is a hint, not a command, and a rejected hint only makes this
         * number closer to the old one — noisier, never wrong in a new way. Called at window close,
         * after the timings are already computed, so the pause it may cause lands between windows
         * rather than inside one.
         */
        private double retainedHeapMib() {
            System.gc();
            Runtime rt = Runtime.getRuntime();
            return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        }
    }

    /**
     * The client's current frame rate, or -1 when this JVM has no client.
     *
     * <p>Reflective because this module is loader- and side-neutral: naming {@code Minecraft} here
     * would put a client-only class on the dedicated server's link path. The read is one {@code int}
     * from another thread, which is exactly as coherent as the F3 overlay's own read of it — a stale
     * sample either side of a window boundary moves a mean over sixty samples by nothing.
     */
    private static int clientFps() {
        try {
            Class<?> mc = Class.forName("net.minecraft.client.Minecraft");
            Object instance = mc.getMethod("getInstance").invoke(null);
            if (instance == null) return -1;
            Object v = mc.getMethod("getFps").invoke(instance);
            return v instanceof Number n ? n.intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Cumulative process CPU time, via the JDK-specific MXBean, reflectively so a JVM without it
     * degrades to -1 rather than failing a scene over a class unrelated to the feature.
     *
     * <p>Resolved on the INTERFACE {@code com.sun.management.OperatingSystemMXBean}, not on the
     * bean's own class. The implementation is {@code com.sun.management.internal.OperatingSystemImpl},
     * which {@code jdk.management} does not export, so reaching the method through
     * {@code bean.getClass()} needs {@code setAccessible} and that throws
     * {@code InaccessibleObjectException} under the module system. The earlier version did exactly
     * that, caught the throwable, and returned -1 — so every CPU figure this framework has ever
     * reported was NaN, on every platform, silently. The interface is exported, and invoking one of
     * its methods on an implementation instance needs no access override at all.
     */
    private static long processCpuTimeNanos() {
        try {
            Object bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            Class<?> iface = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (!iface.isInstance(bean)) return -1L;
            Object v = iface.getMethod("getProcessCpuTime").invoke(bean);
            return v instanceof Number n ? n.longValue() : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }
}
